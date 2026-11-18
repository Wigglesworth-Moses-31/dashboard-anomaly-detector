// The live incident page (add-notifier design N5, N5b). No framework: a Map of incidents, two sorted arrays of ids,
// changes coalesced and applied once per animation frame, lazy details for closed incidents, incremental counters.
"use strict";
import { openStream } from "./rsocket.js";

// ---- state ------------------------------------------------------------------------------------------------
const incidents = new Map();      // id -> view { ev, seq, open, startMs, endMs, label, color, search, row, expanded }
const openIds = [];               // sorted: newest start first
const calmIds = [];               // sorted: newest calm first (add-incident-resolution: waiting for a reason)
const closedIds = [];             // sorted: newest end first
const groupCounts = new Map();    // group label -> incidents today
let pending = new Map();          // id -> newest event not yet applied (coalescing)
let frameScheduled = false;
let staging = null;               // during a (re)connect: the new snapshot, applied at snapshot_complete
let caughtUp = false;
let day = "", zone = "UTC", dayByReceived = false;
let longestMs = 0, lastChangeAt = 0;
let filterText = "";
let groupsDirty = false, countsDirty = false;
const newlyOpened = [];           // OPENED events of this frame, for one notification per burst
const settings = loadSettings();

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

// ---- formatting (formatters are cached: making them is the slow part) ---------------------------------------
let timeFmt, dayFmt;
const numFmt0 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const numFmt1 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const numFmt3 = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 3 });
function setZone(z) {
  zone = z;
  timeFmt = new Intl.DateTimeFormat(undefined, { timeZone: z, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
  dayFmt = new Intl.DateTimeFormat("en-CA", { timeZone: z, year: "numeric", month: "2-digit", day: "2-digit" });
}
setZone(zone);

// Every time in each display zone, the main one first (add-display-time-zones Z2):
//   13:04:10 UTC · 18:34:10 IST · 08:04:10 EST       a zone on another day adds its date: 04:40 IST (18 Nov)
// Formatters are made once per zone list; zone names are cached per zone and day. Browsers name India "IST" only in
// Indian English and New York "EST/EDT" only in US English, so each zone takes the first locale with a real
// abbreviation, else the offset ("GMT+5:30").
let zoneFmts = [];
const labelCache = new Map();
const LABEL_LOCALES = ["en-US", "en-IN", "en-GB", "en-AU"];
function setDisplayZones(list) {
  zoneFmts = (list && list.length ? list : ["UTC", "Asia/Kolkata", "America/New_York"]).map((z) => ({
    zone: z,
    secs: new Intl.DateTimeFormat("en-GB", { timeZone: z, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" }),
    mins: new Intl.DateTimeFormat("en-GB", { timeZone: z, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }),
    date: new Intl.DateTimeFormat("en-CA", { timeZone: z, year: "numeric", month: "2-digit", day: "2-digit" }),
    dm: new Intl.DateTimeFormat("en-GB", { timeZone: z, day: "numeric", month: "short" }),
  }));
}
setDisplayZones(null);
function zoneLabel(z, ms, dateKey) {
  const key = z + "|" + dateKey;
  let label = labelCache.get(key);
  if (label) return label;
  let fallback = null;
  for (const loc of LABEL_LOCALES) {
    const name = new Intl.DateTimeFormat(loc, { timeZone: z, timeZoneName: "short" }).formatToParts(ms).find((p) => p.type === "timeZoneName").value;
    if (fallback == null) fallback = name;
    if (!/[+\-−]/.test(name)) { label = name; break; }
  }
  label = label || fallback;
  labelCache.set(key, label);
  return label;
}
function inZones(ms, seconds) {
  ms = Number(ms);
  const mainDate = zoneFmts[0].date.format(ms);
  return zoneFmts.map((f, i) => {
    const d = i === 0 ? mainDate : f.date.format(ms);
    let s = (seconds ? f.secs : f.mins).format(ms) + " " + zoneLabel(f.zone, ms, d);
    if (i > 0 && d !== mainDate) s += " (" + f.dm.format(ms) + ")";
    return s;
  }).join(" · ");
}
const time = (ms) => inZones(ms, true);
const shortTime = (ms) => inZones(ms, false);
const mainShortTime = (ms) => zoneFmts[0].mins.format(Number(ms));            // the narrow end-time column only
function num(v) { const a = Math.abs(v); return (a >= 100 ? numFmt0 : a >= 1 ? numFmt1 : numFmt3).format(v); }
function duration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + " min";
  return Math.floor(m / 60) + " h " + (m % 60) + " min";
}
function withUnit(v, unit) {
  if (unit === "percent") return num(v) + "%";
  if (unit === "ms" || unit === "millisecond") return num(v) + " ms";
  if (unit === "second") return v < 1 ? num(v * 1000) + " ms" : num(v) + " s";
  return num(v);
}

// ---- signals: colour and how bad ----------------------------------------------------------------------------
const COLOR = { "errors": "red", "success-rate": "red", "error-rate": "red", "latency": "amber", "saturation": "violet", "traffic": "blue" };
const RANK = { red: 4, amber: 3, violet: 2, blue: 1, grey: 0 };
const colorOf = (s) => (s.reason === "silent" ? "grey" : COLOR[s.signal] || "grey");
const isPoints = (s) => s.unit === "percent" || /rate/.test(s.signal || "");
function meter(s) {
  const peak = s.peak_value || 0, normal = s.normal_value || 0;
  if (s.reason === "silent") return { width: 1, text: "silent" };
  if (isPoints(s)) {
    const pts = peak - normal;
    return { width: Math.min(1, Math.abs(pts) / 50), text: (pts > 0 ? "+" : "−") + num(Math.abs(pts)) + " pts" };
  }
  if (normal <= 0) return { width: 1, text: "new" };
  if (peak < normal) {                                    // a drop: how far below normal, never a ratio over zero
    const pct = Math.round((1 - peak / normal) * 100);    // (fix-false-drops design F4)
    return { width: pct / 100, text: peak <= 0 ? "dropped to 0 (−100%)" : "−" + pct + "%" };
  }
  const ratio = peak / normal;
  return { width: Math.min(1, Math.log10(ratio) / 2), text: num(ratio) + "×" };   // 100× = full
}

// ---- incoming lines -----------------------------------------------------------------------------------------
function onLine(text) {
  const msg = JSON.parse(text);
  if (msg.snapshot_complete) {
    day = msg.day;
    dayByReceived = msg.day_by === "received";
    setZone(msg.time_zone);
    if (msg.display_zones) setDisplayZones(msg.display_zones);
    replaceAll(staging);
    staging = null;
    caughtUp = true;
    return;
  }
  lastChangeAt = Date.now();
  const target = staging || pending;           // before snapshot_complete: build the new snapshot aside
  const id = msg.incident_id;
  const prev = target.get(id);
  if (!prev || Number(msg.sequence) > Number(prev.sequence)) target.set(id, msg);
  if (!staging) schedule();
}

function schedule() {
  if (!frameScheduled) { frameScheduled = true; requestAnimationFrame(flush); }
}

/** Applies everything that arrived since the last frame: O(changes). */
function flush() {
  frameScheduled = false;
  const batch = pending;
  pending = new Map();
  for (const ev of batch.values()) apply(ev, true);
  if (newlyOpened.length) { notify(newlyOpened.splice(0)); }
  refreshCounts();
}

/** Throws away the old list and shows a new snapshot, built off-screen in one DocumentFragment per list. */
function replaceAll(snapshot) {
  incidents.clear(); openIds.length = 0; calmIds.length = 0; closedIds.length = 0; groupCounts.clear(); longestMs = 0;
  $("openList").textContent = ""; $("calmList").textContent = ""; $("closedList").textContent = "";
  const views = [];
  for (const ev of (snapshot || new Map()).values()) { const v = makeView(ev); if (v) views.push(v); }
  views.sort((a, b) => order(a) - order(b));
  const frags = { openList: document.createDocumentFragment(), calmList: document.createDocumentFragment(), closedList: document.createDocumentFragment() };
  for (const v of views) {
    incidents.set(v.id, v);
    idsOf(v).push(v.id);
    count(v, +1);
    frags[listOf(v)].appendChild(v.row);
  }
  for (const id in frags) $(id).appendChild(frags[id]);
  pending = new Map();
  refreshCounts(true);
  applyFilter();
}

/** One event: update or insert its incident, moving it between lists if it closed. */
function apply(ev, live) {
  const old = incidents.get(ev.incident_id);
  if (old && Number(ev.sequence) <= old.seq) return;
  const v = makeView(ev);
  if (old) remove(old);
  if (!v) { refreshCounts(); return; }
  incidents.set(v.id, v);
  insertSorted(v);
  count(v, +1);
  // no notification for an incident of only business-as-usual lines (add-line-reasons L4)
  if (live && !old) { v.row.classList.add("fresh"); if (ev.type === "EVENT_TYPE_OPENED" && caughtUp && !v.bauOnly) newlyOpened.push(v); }
  if (filterText) v.row.classList.toggle("hidden", !v.search.includes(filterText));
}

function makeView(ev) {
  const inc = ev.incident || {};
  const open = inc.state !== "INCIDENT_STATE_CLOSED";
  const calm = inc.state === "INCIDENT_STATE_CALM";
  const startMs = Number(inc.started_ms || 0), endMs = Number(inc.ended_ms || 0);
  if (!open && day && !dayByReceived && dayFmt.format(startMs) < day) return null;   // an older day's, now closed
  const series = inc.series || [];
  let color = "grey";
  for (const s of series) { const c = colorOf(s); if (RANK[c] > RANK[color]) color = c; }
  const title = inc.title || ev.incident_id;
  const v = {
    id: ev.incident_id, ev, seq: Number(ev.sequence), open, calm, startMs, endMs, color, title, reason: inc.reason || "",
    resolvedMs: Number(inc.resolved_ms || 0),
    label: title.lastIndexOf(": ") > 0 ? title.slice(0, title.lastIndexOf(": ")) : title,   // labels may contain ": "
    search: (title + " " + series.map((s) => [s.signal, s.metric, s.panel_title, s.panel_path, s.dashboard_title, ...Object.values(s.group_tags || {})].join(" ")).join(" ")).toLowerCase(),
    expanded: false,
    // add-line-reasons: lines still needing a person's reason; BAU lines never do
    waiting: series.filter((s) => !s.bau && !s.explanation).map((s) => s.series_id),
    bauOnly: series.length > 0 && series.every((s) => s.bau),
  };
  forgetAnswered(v, series);
  v.row = open ? openRow(v, inc, series) : closedRow(v);
  if (v.bauOnly) v.row.classList.add("bau-only");
  return v;
}

// ---- rows -----------------------------------------------------------------------------------------------
function openRow(v, inc, series) {
  const row = el("div", "inc s-" + v.color);
  row.dataset.id = v.id;
  const head = el("div", "inc-head");
  head.append(el("span", "title", v.title));
  v.durEl = el("span", "when", v.calm
    ? "calm since " + time(v.endMs) + " · lasted " + duration(v.endMs - v.startMs)
    : "since " + time(v.startMs) + " · " + duration(Date.now() - v.startMs));
  head.append(v.durEl);                       // no dashboard links on the page (add-incident-history H3)
  row.append(head, lines(series, v));
  if (v.waiting.length >= 2) row.append(reasonBox(v.id, "", "Same reason for all " + v.waiting.length + " lines (closes it)"));
  return row;
}

function closedRow(v) {
  const row = el("div", "inc s-" + v.color);
  row.dataset.id = v.id;
  const head = el("div", "inc-head");
  head.append(el("span", "endt", mainShortTime(v.endMs)), el("span", "title", v.title),
    el("span", "when", time(v.startMs) + " – " + time(v.endMs) + " · " + duration(v.endMs - v.startMs)), el("span", "chev", "▸"));
  row.append(head);
  if (v.reason) row.append(el("div", "reason-shown", "Reason: " + v.reason + (v.resolvedMs ? " · given " + shortTime(v.resolvedMs) : "")));
  return row;                                 // details are built only when expanded (lazy)
}

// The affected lines as a small tree per incident (add-incident-history H3):
//   view (dashboard · saved view) → panel group → panel → line tags · value · normal · change · ▸ history
// One Map per level, in first-seen order: O(lines). Full text, wrapped; nothing is cut.
function lines(series, v) {
  const tree = new Map();
  for (const s of series) {
    const view = s.dashboard_title || "Dashboard", group = s.panel_path || "", panel = s.panel_title || "Panel";
    if (!tree.has(view)) tree.set(view, new Map());
    const groups = tree.get(view);
    if (!groups.has(group)) groups.set(group, new Map());
    const panels = groups.get(group);
    if (!panels.has(panel)) panels.set(panel, []);
    panels.get(panel).push(s);
  }
  const box = el("div", "lines tree");
  for (const [view, groups] of tree) {
    box.append(el("div", "t-view", view));
    for (const [group, panels] of groups) {
      if (group) box.append(el("div", "t-group", group));
      for (const [panel, list] of panels) {
        box.append(el("div", group ? "t-panel" : "t-panel t-nogroup", panel));
        for (const s of list) {
          box.append(leaf(s, v.id, group));
          const why = lineReason(s, v);
          if (why) box.append(why);
        }
      }
    }
  }
  return box;
}

function leaf(s, incidentId, group) {
  const c = colorOf(s), m = meter(s);
  const row = el("div", (group ? "t-line" : "t-line t-nogroup") + (s.bau ? " bau" : ""));
  const bar = el("div", "bar"), fill = el("div", "fill f-" + c);
  fill.style.width = Math.max(4, Math.round(m.width * 100)) + "%";
  bar.append(fill);
  const meterEl = el("div", "meter"); meterEl.append(bar, el("span", "c-" + c, m.text));
  const tags = Object.entries(s.group_tags || {}).map(([k, x]) => k + "=" + x).join(", ") || s.metric || "";
  const hist = el("button", "hist", "▸ history");
  hist.dataset.line = s.series_id || ""; hist.dataset.incident = incidentId || "";
  row.append(el("span", "tags", tags),
    el("span", "sig c-" + c, (s.signal || "line").replace("-", " ") + " " + (s.reason || "")),
    el("span", "val", s.reason === "silent" ? "—" : withUnit(s.peak_value || 0, s.unit)),
    el("span", "norm", "normal " + withUnit(s.normal_value || 0, s.unit)),
    meterEl, hist);
  return row;
}

// ---- a line's past: GET /history, shown under the line (add-incident-history H4) ---------------------------
document.addEventListener("click", async (e) => {
  const b = e.target.closest(".hist");
  if (!b) return;
  e.stopPropagation();
  const line = b.closest(".t-line");
  const shown = line.nextElementSibling && line.nextElementSibling.classList.contains("t-past") ? line.nextElementSibling : null;
  if (shown) { shown.remove(); b.textContent = "▸ history"; return; }
  b.textContent = "▾ history";
  const box = el("div", "t-past", "loading…");
  line.after(box);
  try {
    const r = await fetch("/history?line=" + encodeURIComponent(b.dataset.line) + "&before=" + encodeURIComponent(b.dataset.incident));
    renderPast(box, await r.json());
  } catch (err) { box.textContent = "history unavailable"; }
});

function renderPast(box, past) {
  box.textContent = "";
  const hours = Math.round((past.windowMs || 172800000) / 3600000);
  const span = (o) => (o.endedMs && Number(o.endedMs) > 0 ? duration(Number(o.endedMs) - Number(o.startedMs)) : "running");
  const when = (ms) => (dayFmt.format(Number(ms)) === day ? "today " : dayFmt.format(Number(ms)) + " ") + shortTime(Number(ms));
  const desc = (o) => when(o.startedMs) + " · " + span(o) + " · " + withUnit(o.peak, o.unit) + " (normal " + withUnit(o.normal, o.unit) + ") · reason: " + (o.reason || "—");
  box.append(el("div", "p-last", past.last ? "Last time before this: " + desc(past.last) : "No earlier incident in the last " + (hours === 48 ? "2 days" : hours + " h")));
  if ((past.all || []).length) {
    box.append(el("div", "p-head", "In the last " + (hours === 48 ? "2 days" : hours + " h") + " (" + past.all.length + "):"));
    for (const o of past.all) box.append(el("div", "p-item", desc(o)));
  }
}

// ---- reasons, one per line (add-incident-resolution S5, add-line-reasons L4) ---------------------------------
// A box is keyed "incident|line" ("incident|" = every line still waiting). drafts: what was typed, kept when the row
// is rebuilt by a live update; reasons: sent and waiting for the Detector's answer.
const drafts = new Map();
const reasons = new Map();
const MAX_REASON = 500;
const keyOf = (incident, line) => incident + "|" + (line || "");

/** Under a line: "BAU", its reason, or (while the incident is not closed) a box to give one. */
function lineReason(s, v) {
  if (s.bau) return el("div", "t-reason muted", "BAU (business as usual): no reason needed");
  if (s.explanation) return el("div", "t-reason", "Reason: " + s.explanation + (Number(s.explained_ms) ? " · given " + shortTime(Number(s.explained_ms)) : ""));
  if (!v.open) return null;
  // the only waiting line: its reason is the reason for every waiting line, so it closes the incident at once
  const only = v.waiting.length === 1;
  return reasonBox(v.id, only ? "" : s.series_id, only ? "Give reason (closes it)" : "Give reason", "t-reason");
}

function reasonBox(incident, line, label, extra) {
  const key = keyOf(incident, line);
  const box = el("div", "reason-box" + (extra ? " " + extra : ""));
  box.dataset.key = key; box.dataset.incident = incident; box.dataset.line = line || "";
  const input = el("textarea", "reason-in");
  input.maxLength = MAX_REASON; input.rows = 1;
  input.placeholder = line ? "Why did this line change?" : "Why did this happen?";
  input.value = drafts.get(key) || "";
  const counter = el("span", "reason-count", input.value.length + "/" + MAX_REASON);
  const send = el("button", "reason-send", label);
  const state = el("span", "reason-state", "");
  const r = reasons.get(key);
  if (r) { state.textContent = r.text; state.classList.toggle("bad", !!r.bad); }
  box.append(input, counter, send, state);
  return box;
}

/** Forgets sent reasons and drafts the Detector has answered: the incident closed, or that line now has a reason. */
function forgetAnswered(v, series) {
  const answered = new Set(series.filter((s) => s.explanation).map((s) => keyOf(v.id, s.series_id)));
  if (!v.open || v.waiting.length === 0) answered.add(keyOf(v.id, ""));
  for (const key of [...reasons.keys(), ...drafts.keys()]) {
    if (!key.startsWith(v.id + "|")) continue;
    if (!v.open || answered.has(key)) {
      reasons.delete(key); drafts.delete(key);
    }
  }
}

function showReasonState(key, text, bad) {
  const r = reasons.get(key) || {};
  r.text = text; r.bad = bad; reasons.set(key, r);
  const box = document.querySelector('.reason-box[data-key="' + CSS.escape(key) + '"]');
  if (box) { const s = box.querySelector(".reason-state"); s.textContent = text; s.classList.toggle("bad", !!bad); }
}
document.addEventListener("input", (e) => {
  if (!e.target.classList.contains("reason-in")) return;
  const key = e.target.closest(".reason-box").dataset.key;
  drafts.set(key, e.target.value);
  e.target.parentElement.querySelector(".reason-count").textContent = e.target.value.length + "/" + MAX_REASON;
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey && e.target.classList && e.target.classList.contains("reason-in")) {
    e.preventDefault(); e.target.parentElement.querySelector(".reason-send").click();
  }
});
document.addEventListener("click", async (e) => {
  const b = e.target.closest(".reason-send");
  if (!b) return;
  e.stopPropagation();
  const box = b.closest(".reason-box"), key = box.dataset.key;
  const text = (drafts.get(key) || "").trim();
  if (!text || text.length > MAX_REASON) { showReasonState(key, "a reason of 1 to " + MAX_REASON + " characters is needed", true); return; }
  showReasonState(key, "saving…", false);
  const body = { reason: text };
  if (box.dataset.line) body.line = box.dataset.line;
  try {
    const r = await fetch("/incidents/" + encodeURIComponent(box.dataset.incident) + "/reason",
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (r.status === 202) { reasons.get(key).deadline = Date.now() + 10000; return; }   // the Detector's event clears it
    const problem = await r.json().catch(() => ({}));
    showReasonState(key, "not saved: " + (problem.problem || "HTTP " + r.status), true);
  } catch (err) { showReasonState(key, "not saved: the Notifier cannot be reached", true); }
});

// ---- ordered lists: binary search, O(log n) to find, one DOM move ----------------------------------------
// open first (newest start), then calm (newest calm), then closed (newest end)
const order = (v) => (!v.open ? 2e15 - v.endMs : v.calm ? 1e15 - v.endMs : -v.startMs);
const idsOf = (v) => (!v.open ? closedIds : v.calm ? calmIds : openIds);
const listOf = (v) => (!v.open ? "closedList" : v.calm ? "calmList" : "openList");
function position(ids, v) {
  let lo = 0, hi = ids.length;
  const key = order(v);
  while (lo < hi) { const mid = (lo + hi) >> 1; if (order(incidents.get(ids[mid])) <= key) lo = mid + 1; else hi = mid; }
  return lo;
}
function insertSorted(v) {
  const ids = idsOf(v);
  const at = position(ids, v);
  const list = $(listOf(v));
  const next = at < ids.length ? incidents.get(ids[at]).row : null;
  ids.splice(at, 0, v.id);
  list.insertBefore(v.row, next);
}
function remove(v) {
  const ids = idsOf(v);
  const at = ids.indexOf(v.id);                // the arrays hold at most a day's incidents
  if (at >= 0) ids.splice(at, 1);
  v.row.remove();
  incidents.delete(v.id);
  count(v, -1);
}

// ---- incremental counters -------------------------------------------------------------------------------
function count(v, d) {
  groupCounts.set(v.label, (groupCounts.get(v.label) || 0) + d);
  if (groupCounts.get(v.label) <= 0) groupCounts.delete(v.label);
  if (d > 0 && !v.open) longestMs = Math.max(longestMs, v.endMs - v.startMs);   // can only grow during a day
  groupsDirty = true; countsDirty = true;
}
function refreshCounts(force) {
  if (!countsDirty && !force) return;
  countsDirty = false;
  $("cToday").textContent = incidents.size;
  $("cOpen").textContent = openIds.length;
  $("hCalm").textContent = "(" + calmIds.length + ")";
  $("calmSection").classList.toggle("hidden", calmIds.length === 0);
  $("cClosed").textContent = closedIds.length;
  $("cLongest").textContent = longestMs ? duration(longestMs) : "–";
  $("hOpen").textContent = openIds.length ? "(" + openIds.length + ")" : "";
  $("hClosed").textContent = closedIds.length ? "(" + closedIds.length + ")" : "";
  $("quiet").classList.toggle("show", openIds.length === 0);
  $("dayTitle").textContent = "Today · " + (day || "…") + " · " + zone;
  if (groupsDirty) {
    groupsDirty = false;
    const top = [...groupCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 14);  // g is about 30: O(g log g)
    const box = $("groups");
    box.textContent = "";
    for (const [label, n] of top) { const b = el("button", "chip", label + " " + n); b.dataset.filter = label; box.append(b); }
  }
}

// ---- filter: precomputed text, debounced, toggles a class -----------------------------------------------
let filterTimer = 0;
$("filter").addEventListener("input", (e) => {
  clearTimeout(filterTimer);
  filterTimer = setTimeout(() => { filterText = e.target.value.trim().toLowerCase(); applyFilter(); }, 120);
});
function applyFilter() {
  for (const v of incidents.values()) v.row.classList.toggle("hidden", !!filterText && !v.search.includes(filterText));
}
document.addEventListener("keydown", (e) => {
  if (e.target.closest && e.target.closest("input, textarea") && e.target !== $("filter")) return;   // typing a reason
  if (e.key === "/" && document.activeElement !== $("filter")) { e.preventDefault(); $("filter").focus(); }
  if (e.key === "Escape") { $("filter").value = ""; filterText = ""; applyFilter(); $("filter").blur(); }
});

// ---- one click listener for every row (event delegation); lazy details ---------------------------------
$("closedList").addEventListener("click", (e) => {
  if (e.target.closest("a") || e.target.closest(".lines") || e.target.closest(".hist")) return;
  const row = e.target.closest(".inc");
  if (!row) return;
  const v = incidents.get(row.dataset.id);
  if (!v) return;
  v.expanded = !v.expanded;
  row.classList.toggle("expanded", v.expanded);
  if (v.expanded) { v.detail = el("div"); v.detail.append(lines(v.ev.incident.series || [], v)); row.append(v.detail); }
  else if (v.detail) { v.detail.remove(); v.detail = null; }       // freed when collapsed
});
$("groups").addEventListener("click", (e) => {
  const b = e.target.closest(".chip");
  if (!b) return;
  $("filter").value = b.dataset.filter; filterText = b.dataset.filter.toLowerCase(); applyFilter();
});

// ---- one timer: durations of open rows, "last change" ----------------------------------------------------
setInterval(() => {
  const now = Date.now();
  for (const id of openIds) { const v = incidents.get(id); v.durEl.textContent = "since " + time(v.startMs) + " · " + duration(now - v.startMs); }
  for (const [id, r] of reasons) if (r.deadline && now > r.deadline) { r.deadline = 0; showReasonState(id, "not saved: no answer from the Detector yet (it will apply it when it can, or send again)", true); }
  $("lastChange").textContent = lastChangeAt ? "last change " + duration(now - lastChangeAt) + " ago" : "";
}, 1000);

// ---- notifications: one per burst, only for new incidents after the snapshot -----------------------------
function loadSettings() {
  try { return JSON.parse(localStorage.getItem("notifier") || "{}"); } catch (e) { return {}; }
}
function saveSettings() { try { localStorage.setItem("notifier", JSON.stringify(settings)); } catch (e) { /* private window */ } }
function showToggles() {
  $("notifyBtn").textContent = "🔔 " + (settings.notify ? "on" : "off");
  $("notifyBtn").classList.toggle("on", !!settings.notify);
  $("soundBtn").textContent = "🔊 " + (settings.sound ? "on" : "off");
  $("soundBtn").classList.toggle("on", !!settings.sound);
}
$("notifyBtn").addEventListener("click", async () => {
  if (!settings.notify && "Notification" in window && Notification.permission !== "granted") {
    if ((await Notification.requestPermission()) !== "granted") return;
  }
  settings.notify = !settings.notify; saveSettings(); showToggles();
});
$("soundBtn").addEventListener("click", () => { settings.sound = !settings.sound; saveSettings(); showToggles(); if (settings.sound) beep(); });
showToggles();
let audio;
function beep() {
  try {
    audio = audio || new AudioContext();
    const o = audio.createOscillator(), g = audio.createGain();
    o.frequency.value = 880; g.gain.setValueAtTime(0.15, audio.currentTime); g.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + 0.35);
    o.connect(g).connect(audio.destination); o.start(); o.stop(audio.currentTime + 0.35);
  } catch (e) { /* no audio */ }
}
function notify(views) {
  if (settings.sound) beep();
  if (!settings.notify || !("Notification" in window) || Notification.permission !== "granted") return;
  const title = views.length === 1 ? views[0].title : views.length + " new incidents";
  const body = views.length === 1 ? "since " + time(views[0].startMs) : views.map((v) => v.title).slice(0, 4).join("\n");
  try { new Notification(title, { body, tag: "incidents" }); } catch (e) { /* not allowed here */ }
}

// ---- connection: reconnect with backoff, catch up -----------------------------------------------------------
let attempt = 0;
async function connect() {
  setConn("connecting");
  let cfg = { rsocketPort: 7104, route: "incidents.live" };
  try { cfg = await (await fetch("page-config.json", { cache: "no-store" })).json(); } catch (e) { /* use defaults */ }
  staging = new Map();
  caughtUp = false;
  openStream("ws://" + location.hostname + ":" + cfg.rsocketPort, cfg.route, {
    onLine,
    onState(state, reason) {
      if (state === "connected") { attempt = 0; setConn("connected"); return; }
      staging = null;
      setConn("reconnecting", reason);
      const wait = [1000, 2000, 4000][attempt] || 10000;              // 1, 2, 4, then every 10 s
      attempt++;
      setTimeout(connect, reason === "completed" ? 200 : wait);      // a normal end (midnight, zone): at once
    },
  });
}
function setConn(state, reason) {
  const p = $("connPill");
  p.textContent = state; p.className = "pill " + state; p.title = reason || "";
}
connect();

// ---- test hook (task 4.2): n incidents through the normal code path, then a burst, then the filter ----------
window.__bench = async function (n = 2000, burst = 50) {
  const frame = () => new Promise((r) => requestAnimationFrame(() => r()));
  const t0 = Date.parse("2026-11-16T00:00:00Z");
  const make = (i, seq, closed) => ({
    event_id: "b" + i + "-" + seq, incident_id: "bench-" + i, sequence: String(seq), type: closed ? "EVENT_TYPE_CLOSED" : "EVENT_TYPE_OPENED",
    incident: { incident_id: "bench-" + i, state: closed ? "INCIDENT_STATE_CLOSED" : "INCIDENT_STATE_OPEN",
      title: ["/v1/invoices", "billing", "orders", "upstream billing-core", "/v1/telemetry"][i % 5] + ": latency up, errors up",
      started_ms: String(t0 + i * 30000), ended_ms: closed ? String(t0 + i * 30000 + 180000) : undefined,
      series: [{ series_id: "s" + i, metric: "lab.api.latency", unit: "ms", signal: "latency", reason: "up", peak_value: 1200 + i, normal_value: 138,
          dashboard_title: "Shop · Payments", panel_path: "API Metrics", panel_title: "Latencies",
          group_tags: { api: i % 2 ? "/v1/invoices" : "/accounts/_accountid_/devices/_deviceid_/profiles/_profileid_/pause/schedules/_scheduleid_/exceptions/_exceptionid_/history" } },
        { series_id: "e" + i, metric: "lab.api.request.count", unit: "requests", signal: "traffic", reason: "down", peak_value: 0, normal_value: 5,
          dashboard_title: "Shop · Payments", panel_path: "API Gateway Metrics", panel_title: "Request Counts by Status Code", group_tags: { status: "200" } }] } });
  const snap = new Map();
  for (let i = 0; i < n; i++) snap.set("bench-" + i, make(i, 3, i < n - 5));
  const a = performance.now();
  replaceAll(snap);
  void document.body.offsetHeight;            // force layout now: measures the real work, not frame throttling
  const firstPaint = performance.now() - a;
  await frame();
  for (let i = 0; i < burst; i++) onLine(JSON.stringify(make(n - 1 - (i % 5), 10 + i, i % 2 === 0)));
  const b = performance.now();
  flush();
  const burstMs = performance.now() - b;
  const c = performance.now();
  filterText = "billing"; applyFilter();
  const filterMs = performance.now() - c;
  const shown = [...incidents.values()].filter((v) => !v.row.classList.contains("hidden")).length;
  filterText = ""; applyFilter(); $("filter").value = "";
  const mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null;
  return { incidents: incidents.size, firstPaintMs: Math.round(firstPaint), burstOf: burst, burstMs: +burstMs.toFixed(1),
    filterMs: +filterMs.toFixed(1), filterShown: shown, jsHeapMB: mem, domNodes: document.getElementsByTagName("*").length };
};
