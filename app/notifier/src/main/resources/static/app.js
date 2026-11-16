// The live incident page (add-notifier design N5, N5b). No framework: a Map of incidents, two sorted arrays of ids,
// changes coalesced and applied once per animation frame, lazy details for closed incidents, incremental counters.
"use strict";
import { openStream } from "./rsocket.js";

// ---- state ------------------------------------------------------------------------------------------------
const incidents = new Map();      // id -> view { ev, seq, open, startMs, endMs, label, color, search, row, expanded }
const openIds = [];               // sorted: newest start first
const closedIds = [];             // sorted: newest end first
const groupCounts = new Map();    // group label -> incidents today
let pending = new Map();          // id -> newest event not yet applied (coalescing)
let frameScheduled = false;
let staging = null;               // during a (re)connect: the new snapshot, applied at snapshot_complete
let caughtUp = false;
let day = "", zone = "Asia/Kolkata", dayByReceived = false;
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
const time = (ms) => timeFmt.format(ms);
const shortTime = (ms) => time(ms).slice(0, 5);
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
  const ratio = peak >= normal ? peak / normal : normal / Math.max(peak, 1e-9);
  return { width: Math.min(1, Math.log10(ratio) / 2), text: (peak >= normal ? "" : "÷") + num(ratio) + "×" };   // 100× = full
}

// ---- incoming lines -----------------------------------------------------------------------------------------
function onLine(text) {
  const msg = JSON.parse(text);
  if (msg.snapshot_complete) {
    day = msg.day;
    dayByReceived = msg.day_by === "received";
    setZone(msg.time_zone);
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
  incidents.clear(); openIds.length = 0; closedIds.length = 0; groupCounts.clear(); longestMs = 0;
  $("openList").textContent = ""; $("closedList").textContent = "";
  const views = [];
  for (const ev of (snapshot || new Map()).values()) { const v = makeView(ev); if (v) views.push(v); }
  views.sort((a, b) => order(a) - order(b));
  const fo = document.createDocumentFragment(), fc = document.createDocumentFragment();
  for (const v of views) {
    incidents.set(v.id, v);
    (v.open ? openIds : closedIds).push(v.id);
    count(v, +1);
    (v.open ? fo : fc).appendChild(v.row);
  }
  $("openList").appendChild(fo); $("closedList").appendChild(fc);
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
  if (live && !old) { v.row.classList.add("fresh"); if (ev.type === "EVENT_TYPE_OPENED" && caughtUp) newlyOpened.push(v); }
  if (filterText) v.row.classList.toggle("hidden", !v.search.includes(filterText));
}

function makeView(ev) {
  const inc = ev.incident || {};
  const open = inc.state !== "INCIDENT_STATE_CLOSED";
  const startMs = Number(inc.started_ms || 0), endMs = Number(inc.ended_ms || 0);
  if (!open && day && !dayByReceived && dayFmt.format(startMs) < day) return null;   // an older day's, now closed
  const series = inc.series || [];
  let color = "grey";
  for (const s of series) { const c = colorOf(s); if (RANK[c] > RANK[color]) color = c; }
  const title = inc.title || ev.incident_id;
  const v = {
    id: ev.incident_id, ev, seq: Number(ev.sequence), open, startMs, endMs, color, title,
    label: title.lastIndexOf(": ") > 0 ? title.slice(0, title.lastIndexOf(": ")) : title,   // labels may contain ": "
    search: (title + " " + series.map((s) => [s.signal, s.metric, s.panel_title, s.dashboard_title, ...Object.values(s.group_tags || {})].join(" ")).join(" ")).toLowerCase(),
    expanded: false,
  };
  v.row = open ? openRow(v, inc, series) : closedRow(v);
  return v;
}

// ---- rows -----------------------------------------------------------------------------------------------
function openRow(v, inc, series) {
  const row = el("div", "inc s-" + v.color);
  row.dataset.id = v.id;
  const head = el("div", "inc-head");
  head.append(el("span", "title", v.title));
  v.durEl = el("span", "when", "since " + time(v.startMs) + " · " + duration(Date.now() - v.startMs));
  head.append(v.durEl, links(inc));
  row.append(head, lines(series));
  return row;
}

function closedRow(v) {
  const row = el("div", "inc s-" + v.color);
  row.dataset.id = v.id;
  const head = el("div", "inc-head");
  head.append(el("span", "endt", shortTime(v.endMs)), el("span", "title", v.title),
    el("span", "when", time(v.startMs) + " – " + time(v.endMs) + " · " + duration(v.endMs - v.startMs)), el("span", "chev", "▸"));
  row.append(head);
  return row;                                 // details are built only when expanded (lazy)
}

function lines(series) {
  const grid = el("div", "lines");
  for (const s of series) {
    const c = colorOf(s), m = meter(s);
    const bar = el("div", "bar"), fill = el("div", "fill f-" + c);
    fill.style.width = Math.max(4, Math.round(m.width * 100)) + "%";
    bar.append(fill);
    const meterEl = el("div", "meter"); meterEl.append(bar, el("span", "c-" + c, m.text));
    grid.append(
      el("span", "sig c-" + c, (s.signal || "line").replace("-", " ") + " " + (s.reason || "")),
      el("span", "val", s.reason === "silent" ? "—" : withUnit(s.peak_value || 0, s.unit)),
      el("span", "norm", "normal " + withUnit(s.normal_value || 0, s.unit)),
      meterEl,
      el("span", "panel", (s.panel_title || "") + (s.group_tags ? "  ·  " + Object.entries(s.group_tags).map(([k, x]) => k + "=" + x).join(", ") : "")));
  }
  return grid;
}

function links(inc) {
  const box = el("span", "links");
  for (const l of inc.links || []) {
    const a = el("a", null, "↗ " + l.label);
    a.href = l.url; a.target = "_blank"; a.rel = "noopener";
    box.append(a);
  }
  return box;
}

// ---- ordered lists: binary search, O(log n) to find, one DOM move ----------------------------------------
const order = (v) => (v.open ? 0 : 1e15) - (v.open ? v.startMs : v.endMs);    // open first; newest first
function position(ids, v) {
  let lo = 0, hi = ids.length;
  const key = order(v);
  while (lo < hi) { const mid = (lo + hi) >> 1; if (order(incidents.get(ids[mid])) <= key) lo = mid + 1; else hi = mid; }
  return lo;
}
function insertSorted(v) {
  const ids = v.open ? openIds : closedIds;
  const at = position(ids, v);
  const list = $(v.open ? "openList" : "closedList");
  const next = at < ids.length ? incidents.get(ids[at]).row : null;
  ids.splice(at, 0, v.id);
  list.insertBefore(v.row, next);
}
function remove(v) {
  const ids = v.open ? openIds : closedIds;
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
  if (e.key === "/" && document.activeElement !== $("filter")) { e.preventDefault(); $("filter").focus(); }
  if (e.key === "Escape") { $("filter").value = ""; filterText = ""; applyFilter(); $("filter").blur(); }
});

// ---- one click listener for every row (event delegation); lazy details ---------------------------------
$("closedList").addEventListener("click", (e) => {
  if (e.target.closest("a") || e.target.closest(".lines")) return;
  const row = e.target.closest(".inc");
  if (!row) return;
  const v = incidents.get(row.dataset.id);
  if (!v) return;
  v.expanded = !v.expanded;
  row.classList.toggle("expanded", v.expanded);
  if (v.expanded) { v.detail = el("div"); v.detail.append(links(v.ev.incident), lines(v.ev.incident.series || [])); row.append(v.detail); }
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
      series: [{ series_id: "s" + i, metric: "lab.api.latency", unit: "ms", signal: "latency", reason: "up", peak_value: 1200 + i, normal_value: 138, panel_title: "Latencies", group_tags: { api: "/v1/invoices" } },
        { series_id: "e" + i, metric: "lab.api.request.count", unit: "requests", signal: "errors", reason: "up", peak_value: 8, normal_value: 0, panel_title: "Count" }],
      links: [{ label: "Lab: API platform", url: "https://app.datadoghq.com/dashboard/w2f-pqs-ghd" }] } });
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
