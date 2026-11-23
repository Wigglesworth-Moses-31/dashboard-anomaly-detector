// The Anomalies page (calmer-anomalies): the look of the Incidents page of 16-18 Nov 2026, with today's features.
// No whole dashboards are drawn (Datadog shows those): each anomaly is a card in Open, Needs a reason or Closed today,
// under its Region › Dashboard, worst first. Unfolding a card draws the exact graph it happened on with all its lines,
// its other graphs and Calls / Called by folded under it. Values come from /dash/panel and /dash/line; ranges with a
// 10-second interval also take live values (SSE) for the charts that exist. Charts exist only near the screen.
"use strict";
import { openStream } from "./rsocket.js";

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private window */ } } };

// ---- graph size: page units × graph zoom, so browser zoom scales them too (redesign-dashboard R2) -------------------
const ZOOMS = [0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2];
let graphZoom = Number(store.get("graph-zoom")) || 1;
if (!ZOOMS.includes(graphZoom)) graphZoom = 1;
const fpx = (n) => Math.round(n * graphZoom);
function applyZoom() {
  document.documentElement.style.setProperty("--z", String(graphZoom));
  $("zVal").textContent = Math.round(graphZoom * 100) + "%";
  redraw(null);
}
function stepZoom(d) {
  const i = Math.max(0, Math.min(ZOOMS.length - 1, ZOOMS.indexOf(graphZoom) + d));
  graphZoom = ZOOMS[i]; store.set("graph-zoom", String(graphZoom)); applyZoom();
}
$("zIn").addEventListener("click", () => stepZoom(1));
$("zOut").addEventListener("click", () => stepZoom(-1));

// ---- formatting: every time in each display zone, the main one first (add-display-time-zones) ----------------
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
    day: new Intl.DateTimeFormat("en-GB", { timeZone: z, weekday: "short", day: "numeric", month: "short", year: "numeric" }),
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
    else if (i === 0) s = f.dm.format(ms) + " " + s;
    return s;
  }).join(" · ");
}
const shortTime = (ms) => inZones(ms, false);
/** "10:05" in the main zone, every zone on hover (title). */
const hhmm = (ms) => zoneFmts[0].mins.format(Number(ms));
const isToday = (ms) => zoneFmts[0].date.format(Number(ms)) === zoneFmts[0].date.format(Date.now());
const numFmt0 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const numFmt1 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const numFmt3 = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 3 });
const axisFmt2 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
function num(v) { if (v == null || !isFinite(v)) return "—"; const a = Math.abs(v); return (a >= 100 ? numFmt0 : a >= 1 ? numFmt1 : numFmt3).format(v); }
function withUnit(v, unit) {
  if (v == null || !isFinite(v)) return "—";
  if (unit === "percent") return num(v) + "%";
  if (unit === "ms" || unit === "millisecond") return num(v) + " ms";
  if (unit === "second") return v < 1 ? num(v * 1000) + " ms" : num(v) + " s";
  return num(v);
}
/** Axis numbers: short and never long (1.2k, 3.4M, 99.8%, 350 ms). */
function axisNum(v, unit) {
  if (v == null || !isFinite(v)) return "";
  const a = Math.abs(v);
  let t = a >= 1e9 ? numFmt1.format(v / 1e9) + "B" : a >= 1e6 ? numFmt1.format(v / 1e6) + "M" : a >= 1e4 ? numFmt1.format(v / 1e3) + "k"
    : a >= 100 ? numFmt1.format(v) : a >= 1 ? axisFmt2.format(v) : numFmt3.format(v);
  if (unit === "percent") t += "%"; else if (unit === "ms" || unit === "millisecond") t += " ms"; else if (unit === "second") t += " s";
  return t;
}
/** "api: /accounts/_id_/gateways/_mac_/security/state" → "api: /accounts/…/security/state" (full text on hover). */
function midTitle(t, max = 60) {
  t = String(t || "");
  if (t.length <= max) return t;
  const m = /^([^/]*?[:=]\s*)?(\/?[^/]+)(\/.+)$/.exec(t);
  if (m) {
    const parts = m[3].split("/").filter(Boolean);
    if (parts.length > 2) { const short = (m[1] || "") + m[2] + "/…/" + parts.slice(-2).join("/"); if (short.length < t.length) return short; }
  }
  const h = Math.floor((max - 1) / 2);
  return t.slice(0, h) + "…" + t.slice(-h);
}
function duration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + " min";
  const h = Math.floor(m / 60);
  return h < 48 ? h + " h " + (m % 60) + " min" : Math.floor(h / 24) + " d " + (h % 24) + " h";
}
/** Peak against normal in plain words: "3.4×", "−92%", "dropped to 0 (−100%)", "−41 pts", "silent". */
function change(s) {
  const peak = s.peak_value ?? s.peak ?? 0, normal = s.normal_value ?? s.normal ?? 0, dir = s.direction ?? s.reason;
  if (dir === "silent") return "silent";
  if (s.unit === "percent" || /rate/.test(s.signal || "")) { const pts = peak - normal; return (pts > 0 ? "+" : "−") + num(Math.abs(pts)) + " pts"; }
  if (normal <= 0) return "new";
  if (peak < normal) { const pct = Math.round((1 - peak / normal) * 100); return peak <= 0 ? "dropped to 0 (−100%)" : "−" + pct + "%"; }
  return num(peak / normal) + "×";
}
/** How far a line went from normal, as "times normal"; 10 points of a percentage count as 1 ×. */
function sizeOf(s) {
  const peak = s.peak_value ?? s.peak ?? 0, normal = s.normal_value ?? s.normal ?? 0, dir = s.direction ?? s.reason;
  if (dir === "silent") return 1;
  if (s.unit === "percent" || /rate/.test(s.signal || "")) return Math.abs(peak - normal) / 10;
  if (normal <= 0) return peak > 0 ? 2 : 1;
  return peak >= normal ? peak / normal : normal / Math.max(peak, normal * 0.01);
}
const tagsText = (tags) => Object.entries(tags || {}).map(([k, v]) => k + "=" + v).join(", ");
const ARROW = { up: "↑", down: "↓", silent: "· silent" };

// fix-backfill-faults F5: a line is named like in Datadog's legend: its tags, else its formula's alias, else the formula,
// else the metric. Aliases come from the dashboard files in config-repo.
const formulaAlias = new Map();
function noteAliases(widgets) {
  for (const w of widgets || []) {
    const def = w.definition || {};
    if (def.widgets) noteAliases(def.widgets);
    const src = def.source_widget_definition || def;
    const reqs = Array.isArray(src.requests) ? src.requests : Object.values(src.requests || {});
    for (const r of reqs) for (const f of (r && r.formulas) || []) {
      if (!f.alias || !f.formula) continue;
      const prev = formulaAlias.get(f.formula);
      formulaAlias.set(f.formula, prev === undefined || prev === f.alias ? f.alias : null);
    }
  }
}
function lineName(line) {
  if (!line) return "";
  const t = tagsText(line.tags);
  if (t) return t;
  const q = line.query || "", i = q.indexOf("  where  ");
  if (i >= 0) { const f = q.slice(0, i); return formulaAlias.get(f) || f; }
  return line.metric;
}
const seriesName = (s) => (lineInfo.get(s.series_id) ? lineName(lineInfo.get(s.series_id).line) : (tagsText(s.group_tags) || s.metric));

// ---- state ----------------------------------------------------------------------------------------------------
let catalogue = { sites: [], ranges: ["15m", "30m", "1h", "3h", "6h", "12h", "1d"], lines_per_panel: 20 };
let regions = [];                       // [{name, site, dashboards}] from /settings/state
const lineInfo = new Map();             // line id -> {line, places: [{site, dashboard, group, panel}]}
const panelById = new Map();            // panel id -> {panel, dashboard, group, site}
const incidents = new Map();            // incident id -> latest Incident (JSON)
const linesOfIncident = new Map();
const incidentsOfLine = new Map();
let range = store.get("dash-range") || "1h";
const rangeMs = (r) => { const n = parseInt(r, 10); return n * ({ m: 60e3, h: 3600e3, d: 86400e3 })[r.slice(-1)]; };
let judging = {};                       // signal -> "10s" | "5m" (from the Detector's settings, /judging.json)
let region = store.get("region") || "";  // "" = every region

async function getJson(url, opts) {
  const r = await fetch(url, Object.assign({ cache: "no-store" }, opts || {}));
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.problem || "HTTP " + r.status);
  return body;
}

async function loadCatalogue() {
  try {
    const [c, s] = await Promise.all([getJson("/dash/catalogue"), getJson("/settings/state").catch(() => null)]);
    catalogue = c;
    regions = s ? (s.regions || []).map((r) => ({ name: r.name, site: r.site, dashboards: r.dashboards || [] })) : [];
    if (!catalogue.ranges.includes(range)) range = catalogue.ranges.includes("1h") ? "1h" : catalogue.ranges[0];
    lineInfo.clear(); panelById.clear();
    for (const site of catalogue.sites) for (const d of site.dashboards) for (const g of d.groups) for (const p of g.panels) {
      panelById.set(p.id, { panel: p, dashboard: d, group: g, site: site.site });
      for (const l of p.lines) {
        let info = lineInfo.get(l.id);
        if (!info) { info = { line: l, places: [] }; lineInfo.set(l.id, info); }
        info.places.push({ site: site.site, dashboard: d, group: g, panel: p });
      }
    }
  } catch (e) { catalogue.problem = e.message; }
  drawTopBar();
}
let aliasesLoaded = false;
async function loadAliases() {
  if (aliasesLoaded) return;
  aliasesLoaded = true;
  for (const r of regions) for (const entry of r.dashboards || []) {
    try { const res = await fetch("/settings/files/" + entry.file, { cache: "no-store" }); if (res.ok) noteAliases((await res.json()).widgets); } catch (e) { /* skip */ }
  }
  markDirty();
}
async function loadJudging() { try { judging = await getJson("/judging.json"); } catch (e) { judging = {}; } markDirty(); }

// ---- anomalies (the incident stream) ---------------------------------------------------------------------------
const isWaiting = (inc, s) => !s.bau && !s.explanation && (inc.state === "INCIDENT_STATE_OPEN" || inc.state === "INCIDENT_STATE_CALM");
const isOpen = (inc) => inc.state === "INCIDENT_STATE_OPEN";
const isCalm = (inc) => inc.state === "INCIDENT_STATE_CALM";
function indexIncident(inc) {
  const id = inc.incident_id;
  for (const l of linesOfIncident.get(id) || []) incidentsOfLine.get(l)?.delete(id);
  const ls = (inc.series || []).map((s) => s.series_id);
  linesOfIncident.set(id, ls);
  for (const l of ls) { if (!incidentsOfLine.has(l)) incidentsOfLine.set(l, new Set()); incidentsOfLine.get(l).add(id); }
}
/** An anomaly's state: open, waiting (calm, a non-BAU line without a reason), closed, or past (found in backfill). */
function anomalyState(inc) {
  if (inc.found_in_backfill) return "past";
  if (inc.state === "INCIDENT_STATE_CLOSED") return "closed";
  const lines = inc.series || [];
  if (lines.length && lines.every((s) => s.bau)) return "closed";
  if (inc.state === "INCIDENT_STATE_CALM") return lines.some((s) => isWaiting(inc, s)) ? "waiting" : "closed";
  return "open";
}
/** A line's state now: "open", "waiting", "bau" or "". */
function lineState(lineId) {
  let st = "";
  for (const id of incidentsOfLine.get(lineId) || []) {
    const inc = incidents.get(id);
    if (!inc || inc.state === "INCIDENT_STATE_CLOSED" || inc.found_in_backfill) continue;
    const s = (inc.series || []).find((x) => x.series_id === lineId);
    if (!s) continue;
    if (s.bau) { st = st || "bau"; continue; }
    if (isOpen(inc)) return "open";
    if (isCalm(inc) && !s.explanation) st = "waiting";
  }
  return st;
}
/** anomaly-per-graph P4: "site|graph=<view>|w<id>[|tag=value]" → {panel title, split "tag=value" or ""}; null otherwise. */
function graphParts(inc) {
  const key = String(inc.group_key || ""), i = key.indexOf("|graph=");
  if (i < 0) return null;
  const parts = key.slice(i + 7).split("|"), s = inc.series && inc.series[0];
  return { panel: (s && s.panel_title) || "Panel", split: parts.slice(2).join("|") };
}
function serviceName(inc) {
  const key = String(inc.group_key || "");
  const g = key.includes("|") ? key.slice(key.indexOf("|") + 1) : key;
  const eq = g.indexOf("=");
  if (eq < 0) return g || "(no service tag)";
  const tag = g.slice(0, eq), value = g.slice(eq + 1);
  if (tag === "dashboard") return "(no service tag)";
  if (tag === "upstream_service" || tag === "peer.rpc.service") return "upstream " + value;
  return tag + " " + value;
}
/** The card's title parts: ["Success Rates", "api=/v1/invoices"], or ["Request Counts", "all"], or [service]. */
function cardTitle(inc) {
  const g = graphParts(inc);
  return g ? [g.panel, g.split || "all"] : [serviceName(inc)];
}
/** Where an anomaly lives: its first line's place (site, dashboard, panel), else what the incident says. */
function placeOf(inc) {
  for (const s of inc.series || []) {
    const info = lineInfo.get(s.series_id);
    if (info && info.places.length) return info.places[0];
  }
  const s = inc.series && inc.series[0];
  return { site: inc.site || "", dashboard: { id: "?", title: (s && s.dashboard_title) || "Dashboard" }, group: { title: "" }, panel: null };
}
function worstLine(inc) {
  let best = null, size = -1;
  for (const s of inc.series || []) { const z = sizeOf(s) + (s.bau ? -1000 : 0); if (z > size) { best = s; size = z; } }
  return best;
}
function score(inc) {
  const w = worstLine(inc), end = Number(inc.ended_ms) || Date.now();
  return (w ? Math.max(0, sizeOf(w)) : 0) * Math.max(1, (end - Number(inc.started_ms)) / 60000);
}

// ---- the stream: snapshot, then live changes; newest sequence wins ---------------------------------------------
let staging = null, caughtUp = false, lastChangeAt = 0;
const newlyOpened = [];
function onLine(text) {
  const msg = JSON.parse(text);
  if (msg.snapshot_complete) {
    if (msg.display_zones) setDisplayZones(msg.display_zones);
    incidents.clear(); linesOfIncident.clear(); incidentsOfLine.clear();
    for (const ev of staging.values()) apply(ev);
    staging = null; caughtUp = true;
    markDirty(); rerenderLinePanel();
    return;
  }
  lastChangeAt = Date.now();
  if (staging) { const prev = staging.get(msg.incident_id); if (!prev || Number(msg.sequence) > Number(prev.sequence)) staging.set(msg.incident_id, msg); return; }
  apply(msg);
  if (msg.type === "EVENT_TYPE_OPENED" && caughtUp && !msg.incident.found_in_backfill && (msg.incident.series || []).some((s) => !s.bau)) newlyOpened.push(msg.incident);
  if (newlyOpened.length) notify(newlyOpened.splice(0));
  markDirty(); rerenderLinePanel(); clearAnswered(msg.incident);
}
function apply(ev) {
  const inc = ev.incident || {};
  const old = incidents.get(ev.incident_id);
  if (old && Number(old._seq) >= Number(ev.sequence)) return;
  inc._seq = Number(ev.sequence);
  inc.incident_id = inc.incident_id || ev.incident_id;
  incidents.set(ev.incident_id, inc);
  indexIncident(inc);
}

// ---- notifications --------------------------------------------------------------------------------------------
const toggles = (() => { try { return JSON.parse(store.get("notifier") || "{}"); } catch (e) { return {}; } })();
function showToggles() {
  $("notifyBtn").textContent = "🔔 " + (toggles.notify ? "on" : "off"); $("notifyBtn").classList.toggle("on", !!toggles.notify);
  $("soundBtn").textContent = "🔊 " + (toggles.sound ? "on" : "off"); $("soundBtn").classList.toggle("on", !!toggles.sound);
}
$("notifyBtn").addEventListener("click", async () => {
  if (!toggles.notify && "Notification" in window && Notification.permission !== "granted") { if ((await Notification.requestPermission()) !== "granted") return; }
  toggles.notify = !toggles.notify; store.set("notifier", JSON.stringify(toggles)); showToggles();
});
$("soundBtn").addEventListener("click", () => { toggles.sound = !toggles.sound; store.set("notifier", JSON.stringify(toggles)); showToggles(); if (toggles.sound) beep(); });
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
function notify(list) {
  if (toggles.sound) beep();
  if (!toggles.notify || !("Notification" in window) || Notification.permission !== "granted") return;
  const title = list.length === 1 ? list[0].title : list.length + " new anomalies";
  try { new Notification(title, { body: list.length === 1 ? "since " + shortTime(list[0].started_ms) : list.map((i) => i.title).slice(0, 4).join("\n"), tag: "anomalies" }); } catch (e) { /* not allowed */ }
}

// ---- connection ---------------------------------------------------------------------------------------------------
let attempt = 0;
async function connect() {
  setConn("connecting");
  let cfg = { rsocketPort: 7104, route: "incidents.live" };
  try { cfg = await (await fetch("page-config.json", { cache: "no-store" })).json(); } catch (e) { /* defaults */ }
  staging = new Map(); caughtUp = false;
  openStream("ws://" + location.hostname + ":" + cfg.rsocketPort, cfg.route, {
    onLine,
    onState(state, reason) {
      if (state === "connected") { attempt = 0; setConn("connected"); return; }
      staging = null; setConn("reconnecting", reason);
      const wait = [1000, 2000, 4000][attempt] || 10000; attempt++;
      setTimeout(connect, reason === "completed" ? 200 : wait);
    },
  });
}
function setConn(state, reason) { const p = $("connPill"); p.textContent = state === "connected" ? "live" : state; p.className = "pill " + state; p.title = reason || ""; }
setInterval(() => { $("lastChange").textContent = lastChangeAt ? "last change " + duration(Date.now() - lastChangeAt) + " ago" : ""; }, 1000);

// ---- the top bar: region, time range, live, graph size -----------------------------------------------------------
const RANGE_LABEL = { "15m": "Past 15 Minutes", "30m": "Past 30 Minutes", "1h": "Past 1 Hour", "3h": "Past 3 Hours", "6h": "Past 6 Hours", "12h": "Past 12 Hours", "1d": "Past 1 Day" };
const autoInterval = (r) => { for (const i of [10e3, 20e3, 30e3, 60e3, 120e3, 300e3, 600e3]) if (rangeMs(r) / i <= 300) return i; return 600e3; };
let paused = store.get("dash-paused") === "1";
function siteTabs() {
  const seen = new Map();
  for (const r of regions) seen.set(r.site, r.name.toUpperCase());
  for (const s of catalogue.sites) if (!seen.has(s.site)) seen.set(s.site, s.label || s.site);
  return [...seen.entries()].map(([site, label]) => ({ site, label }));
}
const siteName = (site) => (siteTabs().find((t) => t.site === site) || { label: site || "?" }).label;
function drawTopBar() {
  const seg = $("regionSeg"), tabs = siteTabs();
  seg.textContent = "";
  if (tabs.length > 1) {
    if (region && !tabs.some((t) => t.site === region)) region = "";
    for (const t of [{ site: "", label: "All" }, ...tabs]) {
      const b = el("button", region === t.site ? "on" : null, t.label); b.type = "button";
      b.addEventListener("click", () => { region = t.site; store.set("region", region); drawTopBar(); markDirty(); });
      seg.append(b);
    }
  } else region = "";
  const sel = $("rangeSel");
  sel.textContent = "";
  for (const r of catalogue.ranges) { const o = el("option", null, RANGE_LABEL[r] || r); o.value = r; o.selected = r === range; sel.append(o); }
  const live = $("liveBtn");
  live.textContent = paused ? "▶ Paused" : "❚❚ Live"; live.classList.toggle("paused", paused);
  live.title = paused ? "Resume live updates" : "Pause live updates";
}
$("rangeSel").addEventListener("change", () => { range = $("rangeSel").value; store.set("dash-range", range); reloadAll(); });
$("liveBtn").addEventListener("click", () => { paused = !paused; store.set("dash-paused", paused ? "1" : "0"); drawTopBar(); liveChanged(); scheduleRefresh(); });

let catchTimer = 0;
async function pollCatchUp() {
  clearTimeout(catchTimer);
  let since = null, pct = 0;
  try {
    const h = await getJson("/settings/collector");
    const d = (h.components && h.components.collector && h.components.collector.details) || {};
    const s = Object.values(d["backfill-since"] || {}).map(Number).filter(isFinite);
    const p = Object.values(d.backfill || {}).map((x) => parseInt(x, 10)).filter(isFinite);
    if (s.length) { since = Math.min(...s); pct = p.length ? Math.round(p.reduce((a, b) => a + b, 0) / p.length) : 0; }
  } catch (e) { /* the note simply stays away */ }
  const n = $("catchUp");
  n.hidden = since == null;
  if (since != null) {
    n.textContent = "";
    const bar = el("span", "bar"), fill = el("i"); fill.style.width = pct + "%"; bar.append(fill);
    n.append(el("b", null, "Catching up"), el("span", null, duration(Date.now() - since) + " after the last stop"), bar, el("span", null, pct + "%"));
  }
  catchTimer = setTimeout(pollCatchUp, since != null ? 5000 : 60000);
}

// ---- charts: created near the screen, released far from it (add-dashboard-view D5) -----------------------------------
const charts = new Map();             // chart element -> {chart, spec}
const series = new Map();             // line id -> [[t, v], ...] at the range's interval
const dark = () => matchMedia("(prefers-color-scheme: dark)").matches;
const PALETTE = ["#3c78d8", "#e69f00", "#009e73", "#cc79a7", "#56b4e9", "#d55e00", "#7f63b8", "#8c8c2c", "#1b9e9e", "#a0522d"];
const RED = () => (dark() ? "#ff6b6b" : "#d93a3a");
const near = new IntersectionObserver((entries) => { for (const e of entries) if (e.isIntersecting) createChart(e.target); }, { rootMargin: "300px" });
const far = new IntersectionObserver((entries) => { for (const e of entries) if (!e.isIntersecting) releaseChart(e.target); }, { rootMargin: "2000px" });
function watchChart(box, spec) { box._spec = spec; near.observe(box); far.observe(box); }
function unwatchAll(root) { for (const box of root.querySelectorAll(".chart")) { near.unobserve(box); far.unobserve(box); releaseChart(box); } }
function axisColors() { return dark() ? { axis: "#8d95a6", grid: "#2a303b" } : { axis: "#6b7385", grid: "#e3e6ec" }; }

/** A graph: all its lines; the anomaly's line(s) bold, the others faded, labelled bands and a dot at each peak. */
function timeseriesOption(spec) {
  const now = Date.now(), from = now - rangeMs(range);
  let all = spec.lines;
  if (all.length > (catalogue.lines_per_panel || 20)) {                     // the busiest lines, plus the anomaly's
    const peak = (id) => { let m = -Infinity; for (const p of series.get(id) || []) if (p[1] > m) m = p[1]; return m; };
    all = [...all].sort((a, b) => peak(b) - peak(a)).slice(0, catalogue.lines_per_panel || 20);
    for (const x of [...(spec.focus || []), ...(spec.only || [])]) if (spec.lines.includes(x) && !all.includes(x)) all.push(x);
  }
  const legendShown = spec.legend !== false && all.length > 1;
  spec.focus = spec.focus || [];
  const focus = new Set(spec.focus.filter((x) => all.includes(x)));
  const unit = lineInfo.get(all[0])?.line.unit;
  const f = fpx(11), c = axisColors(), red = RED();
  spec._names = new Map();
  const drawn = spec.only && !legendShown ? all.filter((l) => spec.only.has(l)) : all;   // without a legend: only the picked lines
  const s = drawn.map((id) => {
    const i = all.indexOf(id);
    const info = lineInfo.get(id), name = info ? lineName(info.line) : id, hot = focus.has(id);
    spec._names.set(name, id);
    const marks = hot && id === spec.focus[0] ? bandsOf(id, from, unit) : null;     // one band per anomaly: on its worst line
    return {
      id, name, type: "line", showSymbol: false, data: series.get(id) || [], animation: false,
      color: hot ? red : PALETTE[i % PALETTE.length],
      lineStyle: { width: hot ? 2.6 : 1.5, opacity: focus.size && !hot ? 0.3 : 1 }, z: hot ? 5 : 2, emphasis: { focus: "series" },
      markArea: marks && marks.areas.length ? { silent: false, data: marks.areas } : undefined,
      markPoint: marks && marks.points.length ? { symbol: "circle", symbolSize: 9, data: marks.points,
        label: { show: true, position: "top", formatter: (p) => p.value, fontSize: f, fontWeight: 600, color: "inherit" } } : undefined,
    };
  });
  const legend = !legendShown ? undefined : { show: true, top: 0, left: 0, type: "scroll", textStyle: { color: c.axis, fontSize: f }, itemHeight: 6, itemWidth: 12, pageIconSize: 9,
    selected: Object.fromEntries(s.map((x) => [x.name, !spec.only || spec.only.has(x.id)])) };
  const empty = s.every((x) => !x.data.some((p) => p[0] >= from));
  return {
    animation: false, useUTC: true,
    title: { show: empty, text: "No values in the " + (RANGE_LABEL[range] || range).toLowerCase(), left: "center", top: "middle",
      textStyle: { color: c.axis, fontSize: fpx(12), fontWeight: 400 } },
    grid: { left: 6, right: 12, top: legend ? f * 2 + 10 : 10, bottom: 6, containLabel: true },
    tooltip: { trigger: "axis", confine: true, order: "valueDesc", valueFormatter: (v) => withUnit(v, unit), textStyle: { fontSize: fpx(12) }, axisPointer: { type: "line", lineStyle: { color: c.axis } } },
    legend,
    xAxis: { type: "time", min: from, max: now, splitNumber: 4, axisLabel: { color: c.axis, hideOverlap: true, fontSize: f }, axisLine: { lineStyle: { color: c.grid } }, splitLine: { show: false } },
    yAxis: { type: "value", scale: true, splitNumber: 3, axisLabel: { color: c.axis, fontSize: f, hideOverlap: true, formatter: (v) => axisNum(v, unit) }, splitLine: { lineStyle: { color: c.grid } } },
    series: s,
  };
}
/** The line's anomalies on the graph: a labelled band each ("success-rate down · −41 pts") and a dot at its peak. */
function bandsOf(lineId, from, unit) {
  const areas = [], points = [], pts = series.get(lineId) || [], red = RED();
  for (const id of incidentsOfLine.get(lineId) || []) {
    const inc = incidents.get(id); if (!inc) continue;
    const s = (inc.series || []).find((x) => x.series_id === lineId); if (!s) continue;
    const start = Math.max(from, Number(inc.started_ms)), end = Number(inc.ended_ms) || Date.now();
    if (end < from) continue;
    const st = anomalyState(inc), live = st === "open" || st === "waiting", color = live ? red : "#8a919e";
    const dir = s.direction ?? s.reason, label = (s.signal || "line") + " " + (dir || "") + (dir === "silent" ? "" : " · " + change(s));
    areas.push([{ name: label, xAxis: start, itemStyle: { color: live ? "rgba(217,58,58,0.12)" : "rgba(138,145,158,0.16)" },
      label: { show: true, position: "insideTopLeft", color, fontSize: fpx(11), fontWeight: 600 } }, { xAxis: end }]);
    const down = /down|silent/.test(String(s.direction ?? s.reason ?? ""));
    let best = null;
    for (const p of pts) if (p[0] >= start && p[0] <= end && (best == null || (down ? p[1] < best[1] : p[1] > best[1]))) best = p;
    if (best) points.push({ coord: best, value: axisNum(best[1], unit), itemStyle: { color } });
  }
  return { areas, points };
}
function createChart(box) {
  if (charts.has(box) || !box.isConnected || !box._spec) return;
  const spec = box._spec;
  const chart = echarts.init(box, null, { renderer: "canvas" });
  if (spec.group) {                                    // the same moment on every graph of one card (shared crosshair)
    chart.group = spec.group;
    if (!connectedGroups.has(spec.group)) { echarts.connect(spec.group); connectedGroups.add(spec.group); }
  }
  chart.setOption(timeseriesOption(spec));
  charts.set(box, { chart, spec });
  sizeWatch.observe(box);
  box.addEventListener("mousedown", (e) => { box._mod = e.metaKey || e.ctrlKey; }, true);
  chart.on("legendselectchanged", (p) => pickLine(box, spec._names && spec._names.get(p.name), box._mod));
  chart.on("click", (p) => { const id = p.seriesId; if (id && lineInfo.has(id)) openLine(id); });
  ensureData(spec.lines);
  liveChanged();
}
const connectedGroups = new Set();
function releaseChart(box) { const c = charts.get(box); if (!c) return; sizeWatch.unobserve(box); c.chart.dispose(); charts.delete(box); liveChanged(); }
const sizeWatch = new ResizeObserver((entries) => { for (const e of entries) { const c = charts.get(e.target); if (c) c.chart.resize(); } });
/** A legend click shows only that line (again: all); Ctrl/Cmd-click adds or removes one (redesign-dashboard R6). */
function pickLine(box, id, add) {
  const c = charts.get(box);
  if (!c || !id) return;
  const spec = c.spec, only = new Set(spec.only || []);
  if (add) { if (!only.size) spec.lines.forEach((l) => only.add(l)); if (only.has(id)) only.delete(id); else only.add(id); }
  else if (only.size === 1 && only.has(id)) only.clear();
  else { only.clear(); only.add(id); }
  spec.only = only.size && only.size < spec.lines.length ? only : null;
  c.chart.setOption(timeseriesOption(spec), { replaceMerge: ["series"] });
  if (box._onPick) box._onPick();
}
function redraw(filter) {
  for (const [, c] of charts) if (!filter || c.spec.lines.some((l) => filter.has(l))) c.chart.setOption(timeseriesOption(c.spec), { replaceMerge: ["series"] });
}

// ---- values: one /dash/panel call per panel and range, kept until the range changes or a refresh ------------------
const loaded = new Map();             // panel id or "line:<id>" -> time loaded
const loading = new Set();
function sourceOf(lineId) { const p = lineInfo.get(lineId)?.places[0]; return p ? p.panel.id : "line:" + lineId; }
async function ensureData(lines, force) {
  const want = new Set(lines.map(sourceOf));
  await Promise.all([...want].map(async (src) => {
    if (loading.has(src) || (!force && loaded.has(src))) return;
    loading.add(src);
    try {
      const url = src.startsWith("line:") ? "/dash/line?id=" + src.slice(5) + "&range=" + range : "/dash/panel?panel=" + encodeURIComponent(src) + "&range=" + range;
      const data = await getJson(url);
      for (const [id, pts] of Object.entries(data.lines || {})) series.set(id, pts);
      loaded.set(src, Date.now());
      for (const box of document.querySelectorAll(".graph-note[data-src]")) if (box.dataset.src === src) box.remove();
    } catch (e) {
      for (const [box, c] of charts) if (c.spec.lines.some((l) => sourceOf(l) === src)) noteOn(box, "history unavailable: " + e.message, src);
    } finally { loading.delete(src); }
    redraw(new Set(lines));
  }));
}
function noteOn(box, text, src) {
  const g = box.closest(".graph"); if (!g || g.querySelector(".graph-note")) return;
  const n = el("div", "graph-note", text); n.dataset.src = src; g.append(n);
}
function reloadAll() { loaded.clear(); series.clear(); for (const [, c] of charts) ensureData(c.spec.lines, true); liveChanged(); scheduleRefresh(); }

// live: 10-second intervals take each new value (SSE); longer ones re-ask every interval (at least every 30 s)
let liveSource = null, liveKey = "", liveTimer = 0, refreshTimer = 0;
function liveChanged() { clearTimeout(liveTimer); liveTimer = setTimeout(openLive, 500); }
function openLive() {
  const lines = new Set();
  if (!paused && autoInterval(range) <= 10e3) for (const c of charts.values()) c.spec.lines.forEach((l) => lines.add(l));
  const key = [...lines].sort().join(",");
  if (key === liveKey) return;
  liveKey = key;
  if (liveSource) { liveSource.close(); liveSource = null; }
  if (!lines.size) return;
  liveSource = new EventSource("/dash/live?lines=" + key);
  liveSource.addEventListener("points", (e) => {
    const data = JSON.parse(e.data), changed = new Set(), from = Date.now() - rangeMs(range);
    for (const [id, pts] of Object.entries(data)) {
      const cur = series.get(id) || [], first = pts.length ? pts[0][0] : Infinity;
      series.set(id, cur.filter((p) => p[0] >= from && p[0] < first).concat(pts));
      changed.add(id);
    }
    if (changed.size) redraw(changed);
  });
}
function scheduleRefresh() {
  clearTimeout(refreshTimer);
  if (paused || autoInterval(range) <= 10e3) return;
  refreshTimer = setTimeout(async () => {
    const lines = new Set(); for (const c of charts.values()) c.spec.lines.forEach((l) => lines.add(l));
    loaded.clear();
    try { await ensureData([...lines], true); } catch (e) { /* next time */ }
    scheduleRefresh();
  }, Math.max(30e3, autoInterval(range)));
}

// ---- the page: day cards, Open, Needs a reason, Closed today ---------------------------------------------------------
let dirty = false;
function markDirty() { if (!dirty) { dirty = true; requestAnimationFrame(render); } }
const cardEls = new Map();            // incident id -> card element
const rowEls = new Map();             // incident id -> closed row element
const headEls = new Map();            // "section|site|dashboard" -> heading element
const expanded = new Set(JSON.parse(store.get("expanded") || "[]"));
let autoExpandDone = false;
let closedChip = "";

function filterText() { return $("filter").value.trim().toLowerCase(); }
function matches(inc, place, q) {
  if (region && place.site !== region) return false;
  if (!q) return true;
  const hay = [siteName(place.site), place.dashboard.title, ...cardTitle(inc), inc.title || "",
    ...(inc.series || []).map((s) => seriesName(s) + " " + (s.metric || ""))].join(" ").toLowerCase();
  return hay.includes(q);
}

function render() {
  dirty = false;
  const q = filterText(), open = [], waiting = [], closed = [];
  let today = 0, longest = 0;
  for (const inc of incidents.values()) {
    const place = placeOf(inc);
    if (!matches(inc, place, q)) continue;
    const st = anomalyState(inc), start = Number(inc.started_ms), end = Number(inc.ended_ms) || Date.now();
    if (isToday(start)) { today++; longest = Math.max(longest, end - start); }
    if (st === "open") open.push({ inc, place });
    else if (st === "waiting") waiting.push({ inc, place });
    else if (isToday(end)) closed.push({ inc, place, st });
  }
  $("dayTitle").textContent = "Today · " + zoneFmts[0].day.format(Date.now()) + " · times in " + zoneLabel(zoneFmts[0].zone, Date.now(), zoneFmts[0].date.format(Date.now())) + ", every zone on hover";
  $("cToday").textContent = today; $("cOpen").textContent = open.length; $("cWait").textContent = waiting.length;
  $("cClosed").textContent = closed.length; $("cLongest").textContent = longest ? duration(longest) : "–";
  $("hOpen").textContent = open.length; $("hCalm").textContent = waiting.length; $("hClosed").textContent = closed.length;
  $("quiet").classList.toggle("show", !open.length);
  $("calmSection").classList.toggle("hidden", !waiting.length);
  const badge = $("tabBadge"); badge.hidden = !waiting.length; badge.textContent = waiting.length;
  if (!autoExpandDone && open.length && caughtUp) {                         // the worst open anomaly starts unfolded
    autoExpandDone = true;
    if (!expanded.size) expanded.add(open.sort((a, b) => score(b.inc) - score(a.inc))[0].inc.incident_id);
  }
  fillSection($("openList"), "open", open);
  fillSection($("calmList"), "waiting", waiting);
  fillClosed(closed);
  for (const [id, card] of cardEls) if (!card.isConnected) { unwatchAll(card); cardEls.delete(id); }
  for (const [id, row] of rowEls) if (!row.isConnected) rowEls.delete(id);
}

/** Cards under Region › Dashboard headings; headings and cards worst first (size × minutes). */
function fillSection(list, state, items) {
  const groups = new Map();
  for (const x of items) {
    x.score = score(x.inc);
    const key = x.place.site + "|" + x.place.dashboard.id;
    if (!groups.has(key)) groups.set(key, { key, place: x.place, items: [], best: 0 });
    const g = groups.get(key); g.items.push(x); g.best = Math.max(g.best, x.score);
  }
  const nodes = [];
  for (const g of [...groups.values()].sort((a, b) => b.best - a.best)) {
    const hk = state + "|" + g.key;
    let h = headEls.get(hk);
    if (!h) { h = el("div", "t-head"); headEls.set(hk, h); }
    h.textContent = "";
    h.append(el("b", null, siteName(g.place.site)), " › " + g.place.dashboard.title);
    nodes.push(h);
    for (const x of g.items.sort((a, b) => b.score - a.score)) nodes.push(card(x.inc, state));
  }
  const same = nodes.length === list.children.length && nodes.every((n, i) => list.children[i] === n);
  if (!same) list.replaceChildren(...nodes);
}

function judgedTag(s) {
  const p = !s ? "" : judging[s.signal] || judging.general || "";        // a signal no rule names is judged by general
  if (!p) return null;
  const slow = p !== "10s" && /m$/.test(p);
  return el("span", "jtag" + (slow ? "" : " fast"), slow ? parseInt(p, 10) + "-min average" : "every 10 s");
}

/** One anomaly's card (kept across updates, so its charts and typed reasons survive). */
function card(inc, state) {
  const id = inc.incident_id;
  let c = cardEls.get(id);
  if (!c) {
    c = el("div", "inc");
    c._head = el("div", "inc-head"); c._lines = el("div", "lines"); c._reason = el("div", "card-reason"); c._body = el("div", "inc-body");
    c.append(c._head, c._lines, c._reason, c._body);
    c._head.addEventListener("click", (e) => {
      if (e.target.closest("a")) return;
      if (expanded.has(id)) expanded.delete(id); else expanded.add(id);
      store.set("expanded", JSON.stringify([...expanded].slice(-50)));
      drawCard(c, incidents.get(id), c._state);
    });
    cardEls.set(id, c);
  }
  drawCard(c, inc, state);
  return c;
}
function drawCard(c, inc, state) {
  c._state = state;
  const open = expanded.has(inc.incident_id);
  c.className = "inc " + (state === "open" ? "s-red" : "s-amber") + (open ? " expanded" : "");
  const worst = worstLine(inc), [panel, graph] = cardTitle(inc), start = Number(inc.started_ms), end = Number(inc.ended_ms);
  c._head.textContent = "";
  const title = el("span", "title", panel);
  if (graph) { title.append(el("span", "crumb", " › "), midTitle(graph)); title.title = panel + " › " + graph; }
  const when = el("span", "when", hhmm(start) + " – " + (state === "open" ? "now" : hhmm(end)) + " · " + duration((end || Date.now()) - start));
  when.title = shortTime(start);
  c._head.append(el("span", "chev", "▸"), title);
  const tag = judgedTag(worst); if (tag) c._head.append(tag);
  c._head.append(when, datadogLink(inc));
  drawLines(c._lines, inc, state);
  const waitingKey = (inc.series || []).filter((s) => isWaiting(inc, s)).map((s) => s.series_id).join(",");
  if (state === "waiting" && c._reasonKey !== waitingKey) { c._reason.replaceChildren(reasonBoxes(inc, null)); c._reasonKey = waitingKey; }
  else if (state !== "waiting") { c._reason.textContent = ""; c._reasonKey = null; }
  if (open && !c._built) buildBody(c, inc);
  else if (!open && c._built) { unwatchAll(c._body); c._body.textContent = ""; c._built = false; }
  else if (open) for (const box of c._body.querySelectorAll(".chart")) { const ch = charts.get(box); if (ch) ch.chart.setOption(timeseriesOption(ch.spec), { replaceMerge: ["series"] }); }
}
function datadogLink(inc) {
  const span = el("span", "links");
  const url = inc.links && inc.links[0] && inc.links[0].url;
  if (url) { const a = el("a", null, "Open in Datadog ↗"); a.href = url; a.target = "_blank"; a.rel = "noopener"; a.title = "The whole dashboard at this anomaly's time, in Datadog"; span.append(a); }
  return span;
}
function drawLines(box, inc, state) {
  box.textContent = "";
  const color = state === "open" ? "c-red" : state === "waiting" ? "c-amber" : "c-grey", fill = state === "open" ? "f-red" : state === "waiting" ? "f-amber" : "f-grey";
  const list = [...(inc.series || [])].sort((a, b) => (a.bau - b.bau) || (sizeOf(b) - sizeOf(a)));
  for (const s of list) {
    const dir = s.direction ?? s.reason, bau = s.bau ? " bau" : "";
    const tags = el("a", "tags" + bau, seriesName(s)); tags.title = "Details of this line"; tags.addEventListener("click", (e) => { e.preventDefault(); openLine(s.series_id); });
    const meter = el("span", "meter" + bau), bar = el("span", "bar"), f = el("span", "fill " + fill);
    f.style.width = Math.min(100, Math.round(sizeOf(s) * 20)) + "%"; bar.append(f); meter.append(bar, change(s) + (s.bau ? " · BAU" : s.explanation ? " · reason given" : ""));
    box.append(el("span", "sig " + color + bau, (s.signal || "line") + " " + (ARROW[dir] || "")), tags,
      el("span", "val " + color + bau, dir === "silent" ? "—" : withUnit(s.peak_value, s.unit)), el("span", "norm" + bau, "normal " + withUnit(s.normal_value, s.unit)), meter);
  }
}

/** The graph a card's anomaly happened on: its panel's lines, only those of its small graph when split. */
function graphOf(inc) {
  const place = placeOf(inc), panel = place.panel;
  if (!panel) return null;
  const g = graphParts(inc), split = g && g.split ? g.split.split("|")[0] : "";
  const eq = split.indexOf("="), tag = eq > 0 ? split.slice(0, eq) : "", value = eq > 0 ? split.slice(eq + 1) : "";
  const all = panel.lines.map((l) => l.id);
  const lines = tag ? all.filter((id) => (lineInfo.get(id)?.line.tags || {})[tag] === value) : all;
  for (const s of inc.series || []) if (!lines.includes(s.series_id) && lineInfo.has(s.series_id)) lines.push(s.series_id);
  return { place, panel, tag, value, lines };
}
/** Non-BAU lines of an anomaly, worst first: the first one carries the anomaly's band. */
function focusOf(inc, within) {
  return [...(inc.series || [])].filter((s) => !s.bau && (!within || within.includes(s.series_id))).sort((a, b) => sizeOf(b) - sizeOf(a)).map((s) => s.series_id);
}
/** An anomaly grouped by service (incident-scope: service) spans panels: one graph per panel, that service's lines. */
function servicePanels(inc) {
  const key = String(inc.group_key || ""), g = key.includes("|") ? key.slice(key.indexOf("|") + 1) : "", eq = g.indexOf("=");
  const tag = eq > 0 ? g.slice(0, eq) : "", value = eq > 0 ? g.slice(eq + 1) : "";
  const panels = new Map(), dash = placeOf(inc).dashboard.id;
  for (const s of inc.series || []) {
    const info = lineInfo.get(s.series_id); if (!info) continue;
    const p = info.places.find((x) => x.dashboard.id === dash) || info.places[0];
    if (!panels.has(p.panel.id)) {
      const mine = tag && tag !== "dashboard" ? p.panel.lines.filter((l) => (l.tags || {})[tag] === value).map((l) => l.id) : [];
      panels.set(p.panel.id, { panel: p.panel, lines: mine });
    }
    const x = panels.get(p.panel.id); if (!x.lines.includes(s.series_id)) x.lines.push(s.series_id);
  }
  return [...panels.values()];
}
function buildBody(c, inc) {
  c._built = true;
  const body = c._body, id = inc.incident_id, group = "card-" + id;
  body.textContent = "";
  if (!graphParts(inc)) {
    const list = servicePanels(inc);
    if (!list.length) { body.append(el("div", "graph-note", "This anomaly's graphs are not in the catalogue (yet).")); return; }
    const grid = el("div", "small-grid");
    for (const x of list.slice(0, 12)) grid.append(graphBox(x.panel.title + " · " + cardTitle(inc)[0], { lines: x.lines, focus: focusOf(inc, x.lines), group }, false));
    body.append(grid);
    relationsFold(body, inc, placeOf(inc).site, group);
    return;
  }
  const g = graphOf(inc);
  if (!g) { body.append(el("div", "graph-note", "This anomaly's graph is not in the catalogue (yet).")); return; }
  const focus = focusOf(inc);
  const [panel, graph] = cardTitle(inc);
  body.append(graphBox(panel + (graph && graph !== "all" ? " › " + graph : ""), { lines: g.lines, focus, group }, false, inc));
  // other graphs of the same panel (the other values of its split tag)
  if (g.tag) {
    const others = new Map();
    for (const l of g.panel.lines) { const v = (l.tags || {})[g.tag]; if (v != null && v !== g.value) { if (!others.has(v)) others.set(v, []); others.get(v).push(l.id); } }
    if (others.size) {
      const fold = el("details", "fold"), sum = el("summary");
      sum.append(el("b", null, "Other graphs of " + panel), " · " + others.size + " graph" + (others.size === 1 ? "" : "s"));
      const grid = el("div", "small-grid");
      fold.append(sum, grid);
      fold.addEventListener("toggle", () => {
        if (!fold.open || grid.childElementCount) return;
        for (const [v, ls] of [...others.entries()].slice(0, 24)) grid.append(graphBox(g.tag + "=" + v, { lines: ls, group, legend: false }, true));
      });
      body.append(fold);
    }
  }
  relationsFold(body, inc, g.place.site, group);
}
/** Calls / Called by (upstream-callers), from upstreams.yml: folded, with the related lines' graphs of the same kind. */
function relationsFold(body, inc, site, group) {
  let info = null, rel = null;
  for (const s of [...(inc.series || [])].sort((a, b) => sizeOf(b) - sizeOf(a))) {
    const i = lineInfo.get(s.series_id), r = i && relationsOf(i.line, site);
    if (r) { info = i; rel = r; break; }
  }
  if (rel) {
    const fold = el("details", "fold"), sum = el("summary");
    sum.append(el("b", null, rel.title), " · " + (rel.configured ? rel.list.map((x) => x.name + " (" + STATE_SHORT[x.state] + ")").join(", ") || "none" : "callers not configured"));
    fold.append(sum);
    if (!rel.configured) { const a = el("a", null, "Add them in Settings › Upstreams"); a.href = "#settings"; fold.append(a); }
    else {
      const list = el("div", "rel-list"), grid = el("div", "small-grid");
      for (const x of rel.list) list.append(el("span", "st-" + x.state + "-t", (rel.title === "Calls" ? "→ " : "← ") + x.name + " · " + STATE_SHORT[x.state] + (x.text ? " · " + x.text : "")));
      fold.append(list, grid);
      fold.addEventListener("toggle", () => {
        if (!fold.open || grid.childElementCount) return;
        for (const x of rel.list) {
          const same = x.ids.filter((l) => lineInfo.get(l)?.line.unit === info.line.unit && lineInfo.get(l)?.line.kind === info.line.kind);
          if (same.length) grid.append(graphBox(x.name, { lines: same.slice(0, 20), group, legend: same.length > 1 }, true));
        }
        if (!grid.childElementCount) grid.append(el("div", "muted", "No lines of the same kind collected for them."));
      });
      body.append(fold);
    }
  }
}
function graphBox(title, spec, small, inc) {
  const box = el("div", "graph"), head = el("div", "graph-head"), t = el("span", "gt", midTitle(title)); t.title = title;
  const acts = el("span", "acts");
  const big = el("button", null, "⤢ enlarge"); big.type = "button"; big.addEventListener("click", () => openFullScreen(title, spec));
  acts.append(big);
  head.append(t, acts);
  const chart = el("div", "chart" + (small ? " small" : ""));
  box.append(head, chart);
  watchChart(chart, Object.assign({}, spec));
  return box;
}

/** Closed today: compact rows, newest first, with a chip per Region › Dashboard. */
function fillClosed(items) {
  const chips = new Map();
  for (const x of items) { const k = siteName(x.place.site) + " › " + x.place.dashboard.title; chips.set(k, (chips.get(k) || 0) + 1); }
  if (closedChip && !chips.has(closedChip)) closedChip = "";
  const gbox = $("groups"); gbox.textContent = "";
  for (const [k, n] of [...chips.entries()].sort()) {
    const b = el("button", "chip" + (closedChip === k ? " on" : ""), k + " · " + n); b.type = "button";
    b.addEventListener("click", () => { closedChip = closedChip === k ? "" : k; markDirty(); });
    gbox.append(b);
  }
  const rows = items.filter((x) => !closedChip || siteName(x.place.site) + " › " + x.place.dashboard.title === closedChip)
    .sort((a, b) => (Number(b.inc.ended_ms) || Number(b.inc.started_ms)) - (Number(a.inc.ended_ms) || Number(a.inc.started_ms)));
  const nodes = rows.slice(0, 300).map((x) => closedRow(x.inc, x.st, x.place));
  if (!nodes.length) nodes.push(el("div", "muted", "Nothing closed today."));
  $("closedList").replaceChildren(...nodes);
}
function closedRow(inc, st, place) {
  let r = rowEls.get(inc.incident_id);
  if (!r) { r = el("div", "inc"); rowEls.set(inc.incident_id, r); }
  const allBau = (inc.series || []).length && inc.series.every((s) => s.bau);
  r.className = "inc" + (allBau ? " bau-only" : "");
  const start = Number(inc.started_ms), end = Number(inc.ended_ms);
  const [panel, graph] = cardTitle(inc), head = el("div", "inc-head");
  const t = el("span", "title", panel); if (graph) t.append(el("span", "crumb", " › "), midTitle(graph));
  const endt = el("span", "endt", hhmm(start) + "–" + (end ? hhmm(end) : "…")); endt.title = shortTime(start);
  head.append(endt, t);
  const tag = judgedTag(worstLine(inc)); if (tag) head.append(tag);
  if (st === "past") head.append(el("span", "jtag", "past · found in catch-up"));
  const why = st === "past" ? "no reason needed" : allBau ? "BAU" : inc.reason ? "“" + inc.reason + "”" + (inc.resolved_ms ? " · " + hhmm(inc.resolved_ms) : "") : "";
  head.append(el("span", "reason-shown", why), el("span", "where", siteName(place.site) + " › " + place.dashboard.title), datadogLink(inc));
  head.addEventListener("click", (e) => { if (!e.target.closest("a")) { const w = worstLine(inc); if (w) openLine(w.series_id); } });
  r.replaceChildren(head);
  return r;
}

$("filter").addEventListener("input", markDirty);
document.addEventListener("keydown", (e) => {
  if (e.key === "/" && document.activeElement?.tagName !== "INPUT" && document.activeElement?.tagName !== "TEXTAREA" && !$("incidentsView").hidden) { e.preventDefault(); $("filter").focus(); }
  if (e.key === "Escape") { if ($("fullScreen")) closeFullScreen(); else if (currentLine) setLine(null); }
});
$("tabIncidents").addEventListener("click", (e) => {                     // the badge: straight to Needs a reason
  if (location.hash === "#settings") return;
  e.preventDefault();
  if (incidents.size && $("hCalm").textContent !== "0") $("calmSection").scrollIntoView({ behavior: "smooth", block: "start" });
});

// ---- full screen: one graph large with its lines' min, max, average and value ----------------------------------------
function openFullScreen(title, spec) {
  closeFullScreen();
  const ov = el("div", "overlay"); ov.id = "fullScreen";
  const cardBox = el("div", "fs-card"), head = el("div", "fs-head");
  const close = el("button", "x-btn", "✕"); close.type = "button"; close.addEventListener("click", closeFullScreen);
  head.append(el("h2", "fs-title", title), close);
  const chart = el("div", "chart big");
  const table = el("table", "legend");
  cardBox.append(head, chart, table); ov.append(cardBox);
  ov.addEventListener("click", (e) => { if (e.target === ov) closeFullScreen(); });
  document.body.append(ov);
  const big = Object.assign({}, spec, { group: null, legend: false });
  watchChart(chart, big);
  let sortKey = "value";
  const stats = (id) => { const s = series.get(id) || []; if (!s.length) return { min: null, max: null, avg: null, value: null };
    let mn = Infinity, mx = -Infinity, sum = 0; for (const [, v] of s) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; } return { min: mn, max: mx, avg: sum / s.length, value: s[s.length - 1][1] }; };
  const draw = () => {
    table.textContent = "";
    const hr = el("tr");
    for (const [k, t] of [["name", "Line"], ["min", "Min"], ["max", "Max"], ["avg", "Avg"], ["value", "Value"]]) { const th = el("th", k === sortKey ? "on" : null, t); th.addEventListener("click", () => { sortKey = k; draw(); }); hr.append(th); }
    table.append(hr);
    const rows = big.lines.map((id) => ({ id, l: lineInfo.get(id)?.line, s: stats(id) }));
    rows.sort((a, b) => sortKey === "name" ? lineName(a.l).localeCompare(lineName(b.l)) : (b.s[sortKey] ?? -Infinity) - (a.s[sortKey] ?? -Infinity));
    for (const { id, l, s } of rows) {
      const shown = !big.only || big.only.has(id);
      const tr = el("tr", shown ? null : "dim"), a = el("a", null, lineName(l) || id);
      a.title = "Show only this line (Ctrl/Cmd-click: add or remove it)";
      a.addEventListener("click", (e) => { if (charts.has(chart)) pickLine(chart, id, e.metaKey || e.ctrlKey); else { big.only = new Set([id]); draw(); } });
      const td = el("td"); td.append(a); tr.append(td);
      for (const k of ["min", "max", "avg", "value"]) tr.append(el("td", "num", withUnit(s[k], l?.unit)));
      table.append(tr);
    }
  };
  chart._onPick = draw;
  draw(); setTimeout(draw, 1500);
}
function closeFullScreen() { const o = $("fullScreen"); if (o) { unwatchAll(o); o.remove(); } }

// ---- the side panel for one line: details, its anomalies and reasons, Calls / Called by --------------------------------
let currentLine = null;
function openLine(id) { setLine(id); }
function setLine(id) {
  const [path, query] = location.hash.split("?");
  const q = new URLSearchParams(query || "");
  if (id) q.set("line", id); else q.delete("line");
  const s = q.toString();
  location.hash = (path && path !== "#" ? path : "#/anomalies") + (s ? "?" + s : "");
}
function route() {
  $("tabIncidents").classList.toggle("active", location.hash !== "#settings");
  if (location.hash === "#settings") { closeSidePanel(); closeFullScreen(); return; }
  const q = new URLSearchParams(location.hash.split("?")[1] || "");
  const line = q.get("line");
  if (line !== currentLine) { closeSidePanel(); if (line) drawSidePanel(line); }
  if (q.get("at") === "waiting") setTimeout(() => $("calmSection").scrollIntoView({ block: "start" }), 300);
}
window.addEventListener("hashchange", route);
function closeSidePanel() { const o = $("sidePanel"); if (o) o.remove(); currentLine = null; document.body.classList.remove("panel-open"); }

async function drawSidePanel(lineId) {
  currentLine = lineId;
  const info = lineInfo.get(lineId);
  const ov = el("aside", "side-panel"); ov.id = "sidePanel"; ov.dataset.key = lineId;
  document.body.append(ov); document.body.classList.add("panel-open");
  const head = el("div", "sp-head");
  const close = el("button", "x-btn", "✕"); close.type = "button"; close.addEventListener("click", () => setLine(null));
  head.append(el("h2", "sp-title", info ? lineName(info.line) : lineId), close);
  ov.append(head);
  if (!info) { ov.append(el("div", "empty", "This metric is not in the catalogue (yet).")); return; }
  const { line, places } = info, p0 = places[0];
  if (!series.has(lineId)) await ensureData([lineId]);
  const pts = series.get(lineId) || [], last = pts.length ? pts[pts.length - 1] : null;
  const newest = [...(incidentsOfLine.get(lineId) || [])].map((i) => incidents.get(i)).filter(Boolean).sort((a, b) => Number(b.started_ms) - Number(a.started_ms))[0];
  const s = newest ? (newest.series || []).find((x) => x.series_id === lineId) : null;
  const details = el("dl", "details");
  const add = (k, v) => { details.append(el("dt", null, k), el("dd", null, v)); };
  add("Metric", line.metric); add("Question", line.query || "—"); add("Tags", tagsText(line.tags) || "—");
  add("Shown on", places.map((p) => p.dashboard.title + " › " + (p.group.title ? p.group.title + " › " : "") + p.panel.title).join("\n"));
  add("Region", siteName(p0.site) + " (" + p0.site + ")"); add("Unit · kind", (line.unit || "—") + " · " + (line.kind || "—"));
  add("Signal", s ? s.signal + (s.bau ? " (BAU)" : "") : "—"); add("Normal", s ? withUnit(s.normal_value, line.unit) + " (at its last anomaly)" : "—");
  add("Newest value", last ? withUnit(last[1], line.unit) + " · " + shortTime(last[0]) : "no value in this range");
  ov.append(details);
  const rel = relationsBox(line, p0.site);
  if (rel) ov.append(rel);
  ov.append(el("h3", "sp-sub", "Anomalies (last 24 hours)"));
  const list = el("div", "anom-list"); ov.append(list);
  await drawAnomalies(list, lineId);
}
function rerenderLinePanel() {
  const sp = $("sidePanel");
  if (sp && currentLine && sp.dataset.key === currentLine) { const list = sp.querySelector(".anom-list"); if (list) drawAnomalies(list, currentLine); }
}
let pastCache = { line: "", data: null, at: 0 };
async function drawAnomalies(list, lineId) {
  if (pastCache.line !== lineId || Date.now() - pastCache.at > 15000) {
    try { pastCache = { line: lineId, data: await getJson("/history?line=" + lineId), at: Date.now() }; }
    catch (e) { pastCache = { line: lineId, data: { all: [] }, at: Date.now() }; }
  }
  const byId = new Map();
  for (const o of pastCache.data.all || []) byId.set(o.incidentId, { id: o.incidentId, title: o.title, start: Number(o.startedMs), end: Number(o.endedMs), signal: o.signal,
    direction: o.direction, peak: o.peak, normal: o.normal, unit: o.unit, reason: o.reason,
    state: o.reason === "found in backfill" ? "past" : Number(o.endedMs) ? "closed" : "open" });
  for (const id of incidentsOfLine.get(lineId) || []) {
    const inc = incidents.get(id); if (!inc) continue;
    const s = (inc.series || []).find((x) => x.series_id === lineId); if (!s) continue;
    byId.set(id, { id, title: inc.title, start: Number(inc.started_ms), end: Number(inc.ended_ms), signal: s.signal, direction: s.reason, peak: s.peak_value,
      normal: s.normal_value, unit: s.unit, reason: s.bau ? "BAU" : s.explanation || (inc.state === "INCIDENT_STATE_CLOSED" ? inc.reason : ""),
      state: inc.found_in_backfill ? "past" : inc.state === "INCIDENT_STATE_CLOSED" ? "closed" : inc.state === "INCIDENT_STATE_CALM" ? "needs a reason" : "open", inc, s });
  }
  const rows = [...byId.values()].sort((a, b) => b.start - a.start);
  list.textContent = "";
  if (!rows.length) { list.append(el("div", "muted", "No anomaly on this metric in the kept days.")); return; }
  for (const r of rows) {
    const row = el("div", "anom" + (r.s && r.s.bau ? " bau" : "") + " st-" + r.state.replace(/ /g, "-"));
    const head = el("div", "anom-head");
    head.append(el("span", "anom-state", r.state === "open" ? "● open" : r.state === "needs a reason" ? "● needs a reason" : r.state === "past" ? "past · found in catch-up" : "closed"),
      el("span", "anom-what", (r.signal || "line") + " " + (r.direction || "") + (r.direction === "silent" ? "" : " · " + change(r))),
      el("span", "muted", "peak " + withUnit(r.peak, r.unit) + " · normal " + withUnit(r.normal, r.unit)));
    row.append(head, el("div", "muted anom-when", shortTime(r.start) + " · " + (r.end ? "lasted " + duration(r.end - r.start) : "for " + duration(Date.now() - r.start))));
    if (r.state === "past") row.append(el("div", "anom-reason muted", "Found when the gap after a stop was caught up; no reason needed."));
    else if (r.reason) row.append(el("div", "anom-reason", "Reason: " + r.reason + (r.s && r.s.explained_ms ? " · given " + shortTime(r.s.explained_ms) : "")));
    else if (r.inc && isWaiting(r.inc, r.s)) row.append(reasonBoxes(r.inc, lineId));
    else row.append(el("div", "anom-reason muted", "Reason: —"));
    list.append(row);
  }
}

// ---- reasons (per line, or the same for every waiting line of the anomaly) ------------------------------------------
const pending = new Map();      // "incident|line" -> {text, bad, deadline}
const drafts = new Map();
const MAX_REASON = 500;
function reasonBoxes(inc, lineId) {
  const wrap = el("div", "reasons");
  const waiting = (inc.series || []).filter((s) => isWaiting(inc, s));
  if (!lineId || waiting.length === 1) {
    wrap.append(reasonBox(inc.incident_id, "", waiting.length > 1 ? "Same reason for all " + waiting.length + " lines (closes it)" : "Save reason (closes it)"));
    return wrap;
  }
  wrap.append(reasonBox(inc.incident_id, lineId, "Give reason for this line"));
  const others = el("div", "muted others", "This anomaly also waits on: ");
  waiting.filter((s) => s.series_id !== lineId).forEach((s, i) => {
    if (i) others.append(", ");
    const a = el("a", null, seriesName(s)); a.addEventListener("click", () => openLine(s.series_id)); others.append(a);
  });
  wrap.append(others, reasonBox(inc.incident_id, "", "Same reason for all " + waiting.length + " lines (closes it)"));
  return wrap;
}
function reasonBox(incidentId, line, label) {
  const key = incidentId + "|" + line;
  const box = el("form", "reason-box");
  const input = el("textarea", "reason-in"); input.maxLength = MAX_REASON; input.rows = 1; input.placeholder = line ? "Why did this line change?" : "Why did this happen? (1 to 500 characters)";
  input.value = drafts.get(key) || "";
  const count = el("span", "reason-count", input.value.length + " / " + MAX_REASON);
  input.addEventListener("input", () => { drafts.set(key, input.value); count.textContent = input.value.length + " / " + MAX_REASON; });
  input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); box.requestSubmit(); } });
  const send = el("button", "reason-send", label); send.type = "submit";
  const state = el("span", "reason-state", "");
  const p = pending.get(key); if (p) { state.textContent = p.text; state.classList.toggle("bad", !!p.bad); }
  box.append(input, count, send, state);
  box.addEventListener("submit", async (e) => {
    e.preventDefault();
    const text = input.value.trim();
    const show = (t, bad, deadline) => { pending.set(key, { text: t, bad, deadline }); state.textContent = t; state.classList.toggle("bad", !!bad); };
    if (!text || text.length > MAX_REASON) return show("a reason of 1 to " + MAX_REASON + " characters is needed", true);
    show("saving…", false);
    try {
      const body = { reason: text }; if (line) body.line = line;
      const r = await fetch("/incidents/" + encodeURIComponent(incidentId) + "/reason", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (r.status === 202) { show("saving…", false, Date.now() + 10000); return; }
      const b = await r.json().catch(() => ({}));
      show("not saved: " + (b.problem || "HTTP " + r.status), true);
    } catch (err) { show("not saved: the Notifier cannot be reached", true); }
  });
  return box;
}
function clearAnswered(inc) {
  if (!inc) return;
  for (const key of [...pending.keys()]) {
    const [id, line] = key.split("|");
    if (id !== inc.incident_id) continue;
    const s = (inc.series || []).find((x) => x.series_id === line);
    if (inc.state === "INCIDENT_STATE_CLOSED" || (s && s.explanation) || (!line && !(inc.series || []).some((x) => isWaiting(inc, x)))) { pending.delete(key); drafts.delete(key); }
  }
}
setInterval(() => {
  for (const [, p] of pending) if (p.deadline && Date.now() > p.deadline) { p.deadline = 0; p.text = "not saved: no answer from the Detector yet (send again)"; p.bad = true; rerenderLinePanel(); }
}, 1000);

// ---- upstream-callers: which APIs call an upstream (your upstreams.yml) ------------------------------------------------
let upstreams = { caller_tag: "api", upstream_tags: ["upstream_service", "peer.rpc.service"], regions: {} };
async function loadUpstreams() { try { upstreams = await getJson("/settings/upstreams"); } catch (e) { /* none known */ } }
window.addEventListener("upstreams-changed", async () => {
  await loadUpstreams();
  for (const c of cardEls.values()) if (c._built) { unwatchAll(c._body); c._built = false; }
  markDirty();
});
const regionOfSite = (site) => (regions.find((r) => r.site === site) || {}).name || "";
function linesWhere(site, tag, value) {
  const out = [];
  for (const [id, info] of lineInfo) if (info.line.tags && info.line.tags[tag] === value && info.places.some((p) => p.site === site)) out.push(id);
  return out;
}
function stateOfLines(ids) {
  let state = "fine", text = "", best = -1;
  for (const id of ids) {
    const st = lineState(id), rank = st === "open" ? 2 : st === "waiting" ? 1 : 0;
    for (const iid of incidentsOfLine.get(id) || []) {
      const inc = incidents.get(iid); if (!inc) continue;
      const as = anomalyState(inc); if (as !== "open" && as !== "waiting") continue;
      const x = (inc.series || []).find((y) => y.series_id === id); if (!x) continue;
      const sc = rank * 1e6 + sizeOf(x);
      if (sc > best) { best = sc; text = [x.signal, x.direction ?? x.reason].filter(Boolean).join(" ") + " " + change(x); }
    }
    if (rank === 2) state = "open"; else if (rank === 1 && state !== "open") state = "waiting";
  }
  return { state, text, ids };
}
/** For a line: "Called by" (it is an upstream) or "Calls" (it is an API), from upstreams.yml for its region; null if neither. */
function relationsOf(line, site) {
  const map = (upstreams.regions || {})[regionOfSite(site)] || {};
  const tags = line.tags || {};
  const upTag = (upstreams.upstream_tags || []).find((t) => tags[t] && tags[t] !== "N/A");
  if (upTag) {
    const up = tags[upTag], apis = map[up];
    return { title: "Called by", name: up, configured: Array.isArray(apis),
      list: (apis || []).map((api) => Object.assign({ name: api }, stateOfLines(linesWhere(site, upstreams.caller_tag || "api", api)))) };
  }
  const api = tags[upstreams.caller_tag || "api"];
  if (!api) return null;
  const ups = Object.entries(map).filter(([, apis]) => (apis || []).includes(api)).map(([u]) => u);
  if (!ups.length) return null;
  return { title: "Calls", name: api, configured: true,
    list: ups.map((u) => Object.assign({ name: u }, stateOfLines((upstreams.upstream_tags || []).flatMap((t) => linesWhere(site, t, u))))) };
}
const STATE_SHORT = { open: "open", waiting: "needs a reason", fine: "fine" };
function relationsBox(line, site) {
  const rel = relationsOf(line, site);
  if (!rel) return null;
  const box = el("div", "rel-box");
  box.append(el("h3", "sp-sub", rel.title + " (" + rel.name + ")"));
  if (!rel.configured) { const a = el("a", null, "Callers not configured: add them in Settings › Upstreams"); a.href = "#settings"; box.append(a); return box; }
  const list = el("div", "rel-list");
  for (const x of rel.list) list.append(el("span", "st-" + x.state + "-t", x.name + " · " + STATE_SHORT[x.state] + (x.ids.length ? (x.text ? " · " + x.text : "") : " · no lines collected")));
  box.append(list);
  return box;
}

// ---- start -------------------------------------------------------------------------------------------------------
window.addEventListener("viewchange", () => { if (!$("incidentsView").hidden) { markDirty(); redraw(null); } });
(async function start() {
  applyZoom();
  await loadCatalogue();
  markDirty();
  connect();
  pollCatchUp();
  loadUpstreams();
  loadJudging();
  loadAliases();
  route();
  scheduleRefresh();
  setInterval(async () => { await loadCatalogue(); markDirty(); }, 60000);
  setInterval(loadJudging, 60000);
  setInterval(markDirty, 30000);                     // durations and "today" move on
})();

// ---- measuring memory with many unfolded cards (calmer-anomalies 4.3): __benchCards(200, 10) ----------------------------
window.__benchCards = async function (n = 200, linesPer = 10, split = false) {   // split: each anomaly on one small graph (status=200)
  const now = Date.now(), from = now - rangeMs(range), site = "bench.example";
  const dash = { id: "bench", title: "Bench", site, groups: [] };
  for (let k = 0; k < n; k++) {
    const panel = { id: "bench-p" + k, title: "Panel " + k, lines: [] };
    for (let l = 0; l < linesPer; l++) {
      const id = (k * 100 + l).toString(16).padStart(16, "0");
      const line = { id, metric: "bench.metric", tags: { status: String(200 + l) }, unit: "ms", kind: "latency" };
      panel.lines.push(line);
      lineInfo.set(id, { line, places: [{ site, dashboard: dash, group: { title: "" }, panel }] });
      const pts = []; for (let i = 0; i < 300; i++) pts.push([from + i * (now - from) / 300, 100 + 20 * Math.sin(i / 9 + l) + (i === 150 ? 400 : 0)]);
      series.set(id, pts); loaded.set(panel.id, now);
    }
    const iid = "bench-" + k;
    incidents.set(iid, { incident_id: iid, state: "INCIDENT_STATE_OPEN", started_ms: now - 600000, group_key: site + "|graph=bench|w" + k + (split ? "|status=200" : ""), title: "Bench " + k,
      series: [{ series_id: panel.lines[0].id, signal: "latency", reason: "up", peak_value: 500, normal_value: 100, unit: "ms", panel_title: panel.title }] });
    indexIncident(incidents.get(iid));
    expanded.add(iid);
  }
  render();
  await new Promise((r) => setTimeout(r, 100));
  let maxCharts = 0;
  for (let y = 0; y < document.body.scrollHeight; y += innerHeight) { scrollTo(0, y); await new Promise((r) => setTimeout(r, 60)); maxCharts = Math.max(maxCharts, charts.size); }
  scrollTo(0, 0); await new Promise((r) => setTimeout(r, 300));
  const mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null;
  return { cards: n, lines: n * linesPer, chartsAliveMax: maxCharts, chartsAliveNow: charts.size, jsHeapMB: mem };
};
