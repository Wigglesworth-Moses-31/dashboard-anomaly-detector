// The live dashboards (add-dashboard-view D5): one page per region with its dashboards (collapsible, Datadog's group
// order, live line charts), a page per panel and a page per metric (graph and details on top, its anomalies below).
// Anomalies arrive on the incident stream (RSocket); values come from /dash/* (one Store call per dashboard) and, for
// the panels on screen only, from /dash/live. Charts exist only for panels near the screen (IntersectionObserver).
"use strict";
import { openStream } from "./rsocket.js";

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private window */ } } };

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
const numFmt0 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const numFmt1 = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const numFmt3 = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 3 });
function num(v) { if (v == null || !isFinite(v)) return "—"; const a = Math.abs(v); return (a >= 100 ? numFmt0 : a >= 1 ? numFmt1 : numFmt3).format(v); }
function withUnit(v, unit) {
  if (v == null || !isFinite(v)) return "—";
  if (unit === "percent") return num(v) + "%";
  if (unit === "ms" || unit === "millisecond") return num(v) + " ms";
  if (unit === "second") return v < 1 ? num(v * 1000) + " ms" : num(v) + " s";
  return num(v);
}
function duration(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + " min";
  const h = Math.floor(m / 60);
  return h < 48 ? h + " h " + (m % 60) + " min" : Math.floor(h / 24) + " d " + (h % 24) + " h";
}
/** Peak against normal in plain words: "3.4×", "−92%", "dropped to 0 (−100%)", "+12 pts", "silent". */
function change(s) {
  const peak = s.peak_value ?? s.peak ?? 0, normal = s.normal_value ?? s.normal ?? 0, dir = s.direction ?? s.reason;
  if (dir === "silent") return "silent";
  if (s.unit === "percent" || /rate/.test(s.signal || "")) { const pts = peak - normal; return (pts > 0 ? "+" : "−") + num(Math.abs(pts)) + " pts"; }
  if (normal <= 0) return "new";
  if (peak < normal) { const pct = Math.round((1 - peak / normal) * 100); return peak <= 0 ? "dropped to 0 (−100%)" : "−" + pct + "%"; }
  return num(peak / normal) + "×";
}
const tagsText = (tags) => Object.entries(tags || {}).map(([k, v]) => k + "=" + v).join(", ");

// ---- state ----------------------------------------------------------------------------------------------------
let catalogue = { sites: [], ranges: ["15m", "1h", "4h", "1d", "7d"], lines_per_panel: 20 };
let regions = [];                       // [{name, site}] from /settings/state
const lineInfo = new Map();             // line id -> {line, places: [{site, dashboard, group, panelId, panel}]}
const panelById = new Map();            // panel id -> {panel, dashboard, group, site}
const dashById = new Map();             // dashboard id -> {dashboard, site}
const incidents = new Map();            // incident id -> latest Incident (JSON)
const linesOfIncident = new Map();      // incident id -> [line ids]
const incidentsOfLine = new Map();      // line id -> Set(incident id)
let range = store.get("dash-range") || "1h";
const LIVE_RANGES = new Set(["15m", "1h"]);
const rangeMs = (r) => { const n = parseInt(r, 10); return n * ({ m: 60e3, h: 3600e3, d: 86400e3 })[r.slice(-1)]; };

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
    regions = s ? (s.regions || []).map((r) => ({ name: r.name, site: r.site })) : [];
    if (s && s.old && s.old.site && !regions.length) regions = [{ name: "default", site: s.old.site }];
    if (!catalogue.ranges.includes(range)) range = catalogue.ranges[1] || catalogue.ranges[0];
    lineInfo.clear(); panelById.clear(); dashById.clear();
    for (const site of catalogue.sites) {
      for (const d of site.dashboards) {
        dashById.set(d.id, { dashboard: d, site: site.site });
        for (const g of d.groups) {
          for (const p of g.panels) {
            panelById.set(p.id, { panel: p, dashboard: d, group: g, site: site.site });
            for (const l of p.lines) {
              let info = lineInfo.get(l.id);
              if (!info) { info = { line: l, places: [] }; lineInfo.set(l.id, info); }
              info.places.push({ site: site.site, dashboard: d, group: g, panel: p });
            }
          }
        }
      }
    }
  } catch (e) { catalogue.problem = e.message; }
  drawTabs();
}

function siteTabs() {
  const seen = new Map();
  for (const r of regions) seen.set(r.site, r.name.toUpperCase());
  for (const s of catalogue.sites) if (!seen.has(s.site)) seen.set(s.site, s.label || s.site);
  return [...seen.entries()].map(([site, label]) => ({ site, label }));
}

function drawTabs() {
  const nav = $("tabs");
  [...nav.querySelectorAll(".region-tab")].forEach((a) => a.remove());
  const route = parseRoute();
  const site = route.site || (route.panel && panelById.get(route.panel)?.site) || (route.line && lineInfo.get(route.line)?.places[0]?.site);
  for (const t of siteTabs()) {
    const a = el("a", "tab region-tab", t.label);
    a.href = "#/region/" + encodeURIComponent(t.site);
    a.classList.toggle("active", site === t.site);
    nav.insertBefore(a, $("tabSettings"));
  }
}

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

/** A line's state now: "open" (an open anomaly, not BAU), "waiting" (calm, needs a reason), "bau", or "". */
function lineState(lineId) {
  let st = "";
  for (const id of incidentsOfLine.get(lineId) || []) {
    const inc = incidents.get(id);
    if (!inc || inc.state === "INCIDENT_STATE_CLOSED") continue;
    const s = (inc.series || []).find((x) => x.series_id === lineId);
    if (!s) continue;
    if (s.bau) { st = st || "bau"; continue; }
    if (isOpen(inc)) return "open";
    if (isCalm(inc) && !s.explanation) st = "waiting";
  }
  return st;
}

function attentionLines() {
  const open = [], waiting = [];
  for (const [line] of incidentsOfLine) {
    const st = lineState(line);
    if (st === "open") open.push(line); else if (st === "waiting") waiting.push(line);
  }
  return { open, waiting };
}

let attentionDirty = false;
function markAttention() { if (!attentionDirty) { attentionDirty = true; requestAnimationFrame(drawAttention); } }
function drawAttention() {
  attentionDirty = false;
  const { open, waiting } = attentionLines();
  const b = $("attention");
  b.textContent = "● " + open.length + " open · " + waiting.length + (waiting.length === 1 ? " needs" : " need") + " a reason";
  b.classList.toggle("hot", open.length > 0);
  b.classList.toggle("warm", !open.length && waiting.length > 0);
  if (!$("attentionList").hidden) fillAttentionList(open, waiting);
  refreshBadges();
  refreshShading();
}
$("attention").addEventListener("click", () => {
  const list = $("attentionList");
  list.hidden = !list.hidden;
  $("attention").setAttribute("aria-expanded", String(!list.hidden));
  if (!list.hidden) { const { open, waiting } = attentionLines(); fillAttentionList(open, waiting); }
});
function fillAttentionList(open, waiting) {
  const list = $("attentionList");
  list.textContent = "";
  const section = (title, lines, cls) => {
    list.append(el("div", "al-head " + cls, title + " (" + lines.length + ")"));
    if (!lines.length) list.append(el("div", "muted al-empty", "none"));
    for (const id of lines) {
      const info = lineInfo.get(id);
      const since = Math.min(...[...(incidentsOfLine.get(id) || [])].map((i) => Number(incidents.get(i)?.started_ms || Infinity)));
      const a = el("a", "al-item");
      a.href = "#/metric/" + id;
      const where = info ? info.places[0] : null;
      a.append(el("span", "al-where", where ? (siteName(where.site) + " · " + where.dashboard.title + " › " + where.panel.title) : id),
        el("span", "al-tags", info ? (tagsText(info.line.tags) || info.line.metric) : ""),
        el("span", "muted", isFinite(since) ? "since " + shortTime(since) : ""));
      a.addEventListener("click", () => { list.hidden = true; });
      list.append(a);
    }
  };
  section("Open", open, "hot");
  section("Needs a reason", waiting, "warm");
}
const siteName = (site) => (siteTabs().find((t) => t.site === site) || { label: site }).label;

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
    markAttention(); rerenderMetricAnomalies();
    return;
  }
  lastChangeAt = Date.now();
  if (staging) { const prev = staging.get(msg.incident_id); if (!prev || Number(msg.sequence) > Number(prev.sequence)) staging.set(msg.incident_id, msg); return; }
  apply(msg);
  if (msg.type === "EVENT_TYPE_OPENED" && caughtUp && (msg.incident.series || []).some((s) => !s.bau)) newlyOpened.push(msg.incident);
  if (newlyOpened.length) notify(newlyOpened.splice(0));
  markAttention(); rerenderMetricAnomalies(); clearAnswered(msg.incident);
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
function setConn(state, reason) { const p = $("connPill"); p.textContent = state; p.className = "pill " + state; p.title = reason || ""; }
setInterval(() => { $("lastChange").textContent = lastChangeAt ? "last change " + duration(Date.now() - lastChangeAt) + " ago" : ""; }, 1000);

// ---- charts: created near the screen, released far from it; live values for created charts only -----------------
const charts = new Map();             // chart element -> {chart, lines: [ids], panelId, kind}
const series = new Map();             // line id -> [[t, v], ...] for the current range (shared by every chart of the line)
let seriesRange = "";
const dark = () => matchMedia("(prefers-color-scheme: dark)").matches;
const PALETTE = ["#2f6fe0", "#d98a00", "#1f9d55", "#7a4fd6", "#d93a3a", "#0e9aa7", "#b5487d", "#6b7385", "#8c6d1f", "#3e8e41"];

const near = new IntersectionObserver((entries) => { for (const e of entries) if (e.isIntersecting) createChart(e.target); }, { rootMargin: "300px" });
const far = new IntersectionObserver((entries) => { for (const e of entries) if (!e.isIntersecting) releaseChart(e.target); }, { rootMargin: "2000px" });

function watchChart(box, lines, panelId, kind) {
  box._spec = { lines, panelId, kind };
  near.observe(box); far.observe(box);
}
function unwatchAll(root) {
  for (const box of root.querySelectorAll(".chart")) { near.unobserve(box); far.unobserve(box); releaseChart(box); }
}

function topLines(lines, n) {
  if (lines.length <= n) return lines;
  const peak = (id) => { let m = -Infinity; for (const p of series.get(id) || []) if (p[1] > m) m = p[1]; return m; };
  return [...lines].sort((a, b) => peak(b) - peak(a)).slice(0, n);
}

function chartOption(spec) {
  const shown = spec.kind === "small" ? topLines(spec.lines, catalogue.lines_per_panel || 20) : spec.lines;
  const unit = lineInfo.get(shown[0])?.line.unit;
  const now = Date.now(), from = now - rangeMs(range);
  const areas = shadingFor(spec.lines, from);
  const s = shown.map((id, i) => {
    const info = lineInfo.get(id);
    return {
      id, name: info ? (tagsText(info.line.tags) || info.line.metric) : id, type: "line", showSymbol: false, smooth: false,
      data: series.get(id) || [], lineStyle: { width: spec.kind === "metric" ? 2 : 1.2 }, color: PALETTE[i % PALETTE.length],
      emphasis: { focus: "series" }, animation: false,
      markArea: i === 0 && areas.length ? { silent: false, data: areas, label: { show: false } } : undefined,
    };
  });
  const axis = dark() ? "#8d95a6" : "#6b7385", grid = dark() ? "#2a303b" : "#eceff4";
  return {
    animation: false, useUTC: true,
    grid: { left: 62, right: 12, top: 10, bottom: spec.kind === "small" ? 22 : 28 },
    tooltip: { trigger: "axis", confine: true, order: "valueDesc", valueFormatter: (v) => withUnit(v, unit),
      axisPointer: { type: "line" }, appendToBody: false },
    xAxis: { type: "time", min: from, max: now, axisLabel: { color: axis, hideOverlap: true }, axisLine: { lineStyle: { color: grid } }, splitLine: { show: false } },
    yAxis: { type: "value", scale: true, axisLabel: { color: axis, formatter: (v) => withUnit(v, unit) }, splitLine: { lineStyle: { color: grid } } },
    series: s,
  };
}

/**
 * Shaded times of the anomalies on these lines: red where one is open or waiting, grey for BAU and closed ones.
 * Overlapping times are merged (sorted sweep, O(n log n)), so many anomalies never stack into a solid block.
 */
function shadingFor(lines, from) {
  const spans = { live: [], past: [] }, seen = new Set(), names = { live: [], past: [] };
  for (const l of lines) {
    for (const id of incidentsOfLine.get(l) || []) {
      if (seen.has(id)) continue; seen.add(id);
      const inc = incidents.get(id); if (!inc) continue;
      const start = Math.max(from, Number(inc.started_ms)), end = Number(inc.ended_ms) || Date.now();
      if (end < from) continue;
      const allBau = (inc.series || []).every((s) => s.bau);
      const kind = inc.state !== "INCIDENT_STATE_CLOSED" && !allBau ? "live" : "past";
      spans[kind].push([start, end]); names[kind].push(inc.title);
    }
  }
  const out = [];
  for (const kind of ["past", "live"]) {
    const s = spans[kind].sort((a, b) => a[0] - b[0]);
    const merged = [];
    for (const x of s) { const last = merged[merged.length - 1]; if (last && x[0] <= last[1]) last[1] = Math.max(last[1], x[1]); else merged.push([x[0], x[1]]); }
    const label = names[kind].length === 1 ? names[kind][0] : names[kind].length + " anomalies";
    for (const [a, b] of merged) out.push([{ name: label, xAxis: a, itemStyle: { color: kind === "live" ? "rgba(217,58,58,0.10)" : "rgba(138,145,158,0.12)" } }, { xAxis: b }]);
  }
  return out;
}

function createChart(box) {
  if (charts.has(box) || !box.isConnected || !box._spec) return;
  const chart = echarts.init(box, null, { renderer: "canvas" });
  chart.setOption(chartOption(box._spec));
  charts.set(box, Object.assign({ chart }, box._spec));
  chart.on("click", (p) => { if (p.seriesId && box._spec.kind !== "metric") location.hash = "#/metric/" + p.seriesId; });
  liveChanged();
}
function releaseChart(box) {
  const c = charts.get(box);
  if (!c) return;
  c.chart.dispose(); charts.delete(box); liveChanged();
}
function redraw(filter) {
  for (const [box, c] of charts) if (!filter || c.lines.some((l) => filter.has(l))) c.chart.setOption(chartOption(c), { replaceMerge: ["series"] });
}
function refreshShading() { redraw(null); }
window.addEventListener("resize", () => { for (const c of charts.values()) c.chart.resize(); });

// live: one EventSource for the lines of the charts that exist (re-opened, debounced, when that set changes)
let liveSource = null, liveKey = "", liveTimer = 0;
function liveChanged() { clearTimeout(liveTimer); liveTimer = setTimeout(openLive, 500); }
function openLive() {
  const lines = new Set();
  if (LIVE_RANGES.has(range)) for (const c of charts.values()) c.lines.forEach((l) => lines.add(l));
  const key = [...lines].sort().join(",");
  if (key === liveKey) return;
  liveKey = key;
  if (liveSource) { liveSource.close(); liveSource = null; }
  if (!lines.size) return;
  liveSource = new EventSource("/dash/live?lines=" + key);
  liveSource.addEventListener("points", (e) => {
    const data = JSON.parse(e.data), changed = new Set(), from = Date.now() - rangeMs(range);
    for (const [id, pts] of Object.entries(data)) {
      const cur = series.get(id) || [];
      const first = pts.length ? pts[0][0] : Infinity;
      const kept = cur.filter((p) => p[0] >= from && p[0] < first);       // newer points replace the window they cover
      series.set(id, kept.concat(pts));
      changed.add(id);
    }
    if (changed.size) redraw(changed);
  });
}

async function loadRange(url, lines) {
  const data = await getJson(url);
  for (const [id, pts] of Object.entries(data.lines || {})) series.set(id, pts);
  for (const l of lines) if (!data.lines || !data.lines[l]) series.set(l, []);
  return data;
}

// ---- router ------------------------------------------------------------------------------------------------------
function parseRoute() {
  const h = location.hash;
  let m;
  if ((m = /^#\/region\/(.+)$/.exec(h))) return { page: "region", site: decodeURIComponent(m[1]) };
  if ((m = /^#\/panel\/([0-9a-f]+)$/.exec(h))) return { page: "panel", panel: m[1] };
  if ((m = /^#\/metric\/([0-9a-f]+)$/.exec(h))) return { page: "metric", line: m[1] };
  if (h === "#settings") return { page: "settings" };
  return { page: "home" };
}
let current = null;
async function route() {
  const r = parseRoute();
  if (r.page === "settings") { leave(); drawTabs(); return; }
  if (r.page === "home") { const first = siteTabs()[0]; if (first) { location.replace("#/region/" + encodeURIComponent(first.site)); return; } }
  leave();
  drawTabs();
  const main = $("incidentsView");
  main.textContent = "";
  current = r;
  if (r.page === "region") drawRegion(main, r.site);
  else if (r.page === "panel") drawPanel(main, r.panel);
  else if (r.page === "metric") drawMetric(main, r.line);
  else main.append(el("div", "empty", catalogue.problem ? "Dashboards unavailable: " + catalogue.problem : "No dashboards yet: add a region and its dashboards in Settings."));
}
function leave() { unwatchAll($("incidentsView")); series.clear(); seriesRange = ""; liveChanged(); current = null; }
window.addEventListener("hashchange", route);

function rangePicker(onChange) {
  const box = el("div", "range");
  for (const r of catalogue.ranges) {
    const b = el("button", "range-btn" + (r === range ? " on" : ""), r + (LIVE_RANGES.has(r) ? " ●" : ""));
    b.type = "button"; b.title = LIVE_RANGES.has(r) ? "10-second values, live" : "per-minute values";
    b.addEventListener("click", () => { range = r; store.set("dash-range", r); onChange(); });
    box.append(b);
  }
  box.append(el("span", "muted range-note", "Each 10-second step shows its highest value, so peaks can be higher than on Datadog's own graphs."));
  return box;
}
function breadcrumb(parts) {
  const nav = el("nav", "crumbs");
  parts.forEach((p, i) => {
    if (i) nav.append(el("span", "crumb-sep", "›"));
    if (p.href) { const a = el("a", null, p.text); a.href = p.href; nav.append(a); } else nav.append(el("span", "crumb-here", p.text));
  });
  return nav;
}

// ---- the region page -----------------------------------------------------------------------------------------
function drawRegion(main, site) {
  const s = catalogue.sites.find((x) => x.site === site);
  main.append(el("h1", "page-title", siteName(site) + " · " + site));
  main.append(rangePicker(() => route()));
  if (!s || !s.dashboards.length) { main.append(el("div", "empty", "No dashboards collected for this region yet.")); return; }
  for (const d of s.dashboards) main.append(dashboardCard(d));
}

function dashboardCard(d) {
  const card = el("section", "dash");
  card.dataset.dash = d.id;
  const head = el("button", "dash-head");
  head.type = "button";
  const open = store.get("dash-open:" + d.id) === "1";
  head.setAttribute("aria-expanded", String(open));
  head.append(el("span", "chev", open ? "▾" : "▸"), el("span", "dash-title", d.title), el("span", "muted", d.panelCount + " panels · " + d.lineCount + " lines"),
    el("span", "badge hot"), el("span", "badge warm"));
  const body = el("div", "dash-body");
  card.append(head, body);
  head.addEventListener("click", () => {
    const now = head.getAttribute("aria-expanded") !== "true";
    store.set("dash-open:" + d.id, now ? "1" : "0");
    head.setAttribute("aria-expanded", String(now)); head.querySelector(".chev").textContent = now ? "▾" : "▸";
    if (now) expand(d, body); else { unwatchAll(body); body.textContent = ""; }
  });
  badges(card, d);
  if (open) expand(d, body);
  return card;
}

function dashLines(d) { const out = new Set(); d.groups.forEach((g) => g.panels.forEach((p) => p.lines.forEach((l) => out.add(l.id)))); return out; }
function badges(card, d) {
  let open = 0, waiting = 0;
  for (const l of dashLines(d)) { const st = lineState(l); if (st === "open") open++; else if (st === "waiting") waiting++; }
  const [hot, warm] = card.querySelectorAll(".dash-head .badge");
  hot.textContent = open ? "● " + open + " open" : ""; warm.textContent = waiting ? "● " + waiting + " need" + (waiting === 1 ? "s" : "") + " a reason" : "";
  for (const p of card.querySelectorAll(".panel")) p.classList.toggle("red-edge", p._lines.some((l) => lineState(l) === "open"));
}
function refreshBadges() {
  for (const card of document.querySelectorAll(".dash")) { const d = dashById.get(card.dataset.dash); if (d) badges(card, d.dashboard); }
}

async function expand(d, body) {
  body.textContent = "";
  const status = el("div", "muted", "loading…");
  body.append(status);
  try { await loadRange("/dash/range?dashboard=" + encodeURIComponent(d.id) + "&range=" + range + "&width=" + Math.max(200, Math.round(innerWidth / 3)), dashLines(d)); status.remove(); }
  catch (e) { status.textContent = "history unavailable (" + e.message + "); live values and anomalies still arrive"; status.className = "s-warn"; }
  for (const g of d.groups) {
    const gBox = el("div", "group");
    const gOpen = store.get("group-open:" + d.id + "|" + g.title) !== "0";
    const gHead = el("button", "group-head", (gOpen ? "▾ " : "▸ ") + (g.title || "Panels") + " (" + g.panels.length + ")");
    gHead.type = "button";
    const grid = el("div", "panels");
    gBox.append(gHead, grid);
    const fill = () => { for (const p of g.panels) grid.append(panelCard(p)); };
    gHead.addEventListener("click", () => {
      const now = grid.childElementCount === 0;
      store.set("group-open:" + d.id + "|" + g.title, now ? "1" : "0");
      gHead.textContent = (now ? "▾ " : "▸ ") + (g.title || "Panels") + " (" + g.panels.length + ")";
      if (now) fill(); else { unwatchAll(grid); grid.textContent = ""; }
    });
    if (gOpen) fill();
    body.append(gBox);
  }
  badges(body.closest(".dash"), d);
}

function panelCard(p) {
  const box = el("div", "panel");
  box._lines = p.lines.map((l) => l.id);
  const head = el("a", "panel-title", p.title);
  head.href = "#/panel/" + p.id;
  const chart = el("div", "chart small");
  box.append(head, chart);
  const n = catalogue.lines_per_panel || 20;
  if (p.lines.length > n) { const more = el("a", "muted more", "+ " + (p.lines.length - n) + " more"); more.href = "#/panel/" + p.id; box.append(more); }
  box.classList.toggle("red-edge", box._lines.some((l) => lineState(l) === "open"));
  watchChart(chart, box._lines, p.id, "small");
  return box;
}

// ---- the panel page ----------------------------------------------------------------------------------------------
async function drawPanel(main, panelId) {
  const info = panelById.get(panelId);
  if (!info) { main.append(el("div", "empty", "This panel is not in the catalogue (yet).")); return; }
  const { panel, dashboard, group, site } = info;
  main.append(breadcrumb([{ text: siteName(site), href: "#/region/" + encodeURIComponent(site) }, { text: dashboard.title }, { text: group.title || "Panels" }, { text: panel.title }]));
  main.append(el("h1", "page-title", panel.title));
  main.append(rangePicker(() => route()));
  const chart = el("div", "chart big");
  main.append(chart);
  const lines = panel.lines.map((l) => l.id);
  try { await loadRange("/dash/panel?panel=" + panelId + "&range=" + range + "&width=1000", lines); }
  catch (e) { main.insertBefore(el("div", "s-warn", "history unavailable (" + e.message + ")"), chart); }
  if (current?.panel !== panelId) return;
  watchChart(chart, lines, panelId, "big");
  const search = el("input", "legend-search"); search.type = "search"; search.placeholder = "filter lines…";
  const table = el("table", "legend");
  main.append(search, table);
  let sortKey = "newest";
  const newest = (id) => { const s = series.get(id) || []; return s.length ? s[s.length - 1][1] : null; };
  const peakOf = (id) => { let m = null; for (const p of series.get(id) || []) if (m == null || p[1] > m) m = p[1]; return m; };
  const draw = () => {
    table.textContent = "";
    const hr = el("tr");
    for (const [k, t] of [["name", "Line"], ["state", "State"], ["newest", "Newest"], ["peak", "Peak in range"]]) {
      const th = el("th", k === sortKey ? "on" : null, t); th.addEventListener("click", () => { sortKey = k; draw(); }); hr.append(th);
    }
    table.append(hr);
    const q = search.value.trim().toLowerCase();
    const rows = panel.lines.filter((l) => !q || (tagsText(l.tags) + " " + l.metric).toLowerCase().includes(q));
    const val = (l) => sortKey === "name" ? tagsText(l.tags) : sortKey === "state" ? lineState(l.id) : sortKey === "newest" ? newest(l.id) ?? -Infinity : peakOf(l.id) ?? -Infinity;
    rows.sort((a, b) => sortKey === "name" || sortKey === "state" ? String(val(a)).localeCompare(String(val(b))) : val(b) - val(a));
    for (const l of rows) {
      const tr = el("tr");
      const a = el("a", null, tagsText(l.tags) || l.metric); a.href = "#/metric/" + l.id;
      const st = lineState(l.id);
      const td = el("td"); td.append(a);
      tr.append(td, el("td", "state " + st, st === "open" ? "● open" : st === "waiting" ? "● needs a reason" : st === "bau" ? "BAU" : ""),
        el("td", "num", withUnit(newest(l.id), l.unit)), el("td", "num", withUnit(peakOf(l.id), l.unit)));
      table.append(tr);
    }
  };
  search.addEventListener("input", draw);
  draw();
}

// ---- the metric page -----------------------------------------------------------------------------------------------
async function drawMetric(main, lineId) {
  const info = lineInfo.get(lineId);
  if (!info) { main.append(el("div", "empty", "This metric is not in the catalogue (yet).")); return; }
  const { line, places } = info;
  const p0 = places[0];
  main.append(breadcrumb([{ text: siteName(p0.site), href: "#/region/" + encodeURIComponent(p0.site) }, { text: p0.dashboard.title },
    { text: p0.group.title || "Panels" }, { text: p0.panel.title, href: "#/panel/" + p0.panel.id }, { text: tagsText(line.tags) || line.metric }]));
  main.append(el("h1", "page-title", (tagsText(line.tags) || line.metric)));
  main.append(rangePicker(() => route()));
  const top = el("div", "metric-top");
  const chart = el("div", "chart metric");
  const details = el("dl", "details");
  top.append(chart, details);
  main.append(top);
  try { await loadRange("/dash/line?id=" + lineId + "&range=" + range + "&width=1000", [lineId]); }
  catch (e) { top.insertBefore(el("div", "s-warn", "history unavailable (" + e.message + ")"), chart); }
  if (current?.line !== lineId) return;
  watchChart(chart, [lineId], p0.panel.id, "metric");
  const pts = series.get(lineId) || [];
  const last = pts.length ? pts[pts.length - 1] : null;
  const newestIncident = [...(incidentsOfLine.get(lineId) || [])].map((i) => incidents.get(i)).filter(Boolean)
    .sort((a, b) => Number(b.started_ms) - Number(a.started_ms))[0];
  const s = newestIncident ? (newestIncident.series || []).find((x) => x.series_id === lineId) : null;
  const add = (k, v) => { details.append(el("dt", null, k), el("dd", null, v)); };
  add("Metric", line.metric);
  add("Question", line.query || "—");
  add("Tags", tagsText(line.tags) || "—");
  add("Shown on", places.map((p) => p.dashboard.title + " › " + (p.group.title ? p.group.title + " › " : "") + p.panel.title).join("\n"));
  add("Region", siteName(p0.site) + " (" + p0.site + ")");
  add("Unit · kind", (line.unit || "—") + " · " + (line.kind || "—"));
  add("Judged as", s ? s.signal + (s.bau ? " (BAU)" : "") : "the rule for " + (line.kind || "its kind") + " (no anomaly yet)");
  add("Normal", s ? withUnit(s.normal_value, line.unit) + " (at its last anomaly)" : "—");
  add("Newest value", last ? withUnit(last[1], line.unit) + " · " + shortTime(last[0]) : "no value in this range");
  const below = el("section", "anomalies");
  below.append(el("h2", null, "Anomalies"));
  const list = el("div", "anom-list");
  below.append(list);
  main.append(below);
  main._anomalies = { list, lineId };
  await drawAnomalies(list, lineId);
}

let pastCache = { line: "", data: null, at: 0 };
async function drawAnomalies(list, lineId) {
  if (pastCache.line !== lineId || Date.now() - pastCache.at > 15000) {
    try { pastCache = { line: lineId, data: await getJson("/history?line=" + lineId), at: Date.now() }; }
    catch (e) { pastCache = { line: lineId, data: { all: [] }, at: Date.now() }; }
  }
  const byId = new Map();
  for (const o of pastCache.data.all || []) byId.set(o.incidentId, { id: o.incidentId, title: o.title, start: Number(o.startedMs), end: Number(o.endedMs), signal: o.signal,
    direction: o.direction, peak: o.peak, normal: o.normal, unit: o.unit, reason: o.reason, state: Number(o.endedMs) ? "closed" : "open" });
  for (const id of incidentsOfLine.get(lineId) || []) {
    const inc = incidents.get(id); if (!inc) continue;
    const s = (inc.series || []).find((x) => x.series_id === lineId); if (!s) continue;
    byId.set(id, { id, title: inc.title, start: Number(inc.started_ms), end: Number(inc.ended_ms), signal: s.signal, direction: s.reason, peak: s.peak_value,
      normal: s.normal_value, unit: s.unit, reason: s.bau ? "BAU" : s.explanation || (inc.state === "INCIDENT_STATE_CLOSED" ? inc.reason : ""),
      state: inc.state === "INCIDENT_STATE_CLOSED" ? "closed" : inc.state === "INCIDENT_STATE_CALM" ? "needs a reason" : "open", inc, s });
  }
  const rows = [...byId.values()].sort((a, b) => b.start - a.start);
  list.textContent = "";
  if (!rows.length) { list.append(el("div", "muted", "No anomaly on this metric in the kept days.")); return; }
  for (const r of rows) {
    const row = el("div", "anom" + (r.s && r.s.bau ? " bau" : "") + " st-" + r.state.replace(/ /g, "-"));
    const head = el("div", "anom-head");
    head.append(el("span", "anom-state", r.state === "open" ? "● open" : r.state === "needs a reason" ? "● needs a reason" : "closed"),
      el("span", "anom-what", (r.signal || "line") + " " + (r.direction || "") + (r.direction === "silent" ? "" : " · " + change(r))),
      el("span", "muted", "peak " + withUnit(r.peak, r.unit) + " · normal " + withUnit(r.normal, r.unit)));
    const when = el("div", "muted anom-when", shortTime(r.start) + " · " + (r.end ? "lasted " + duration(r.end - r.start) : "for " + duration(Date.now() - r.start)));
    row.append(head, when);
    if (r.reason) row.append(el("div", "anom-reason", "Reason: " + r.reason + (r.s && r.s.explained_ms ? " · given " + shortTime(r.s.explained_ms) : "")));
    else if (r.inc && isWaiting(r.inc, r.s)) row.append(reasonBoxes(r.inc, lineId));
    else row.append(el("div", "anom-reason muted", "Reason: —"));
    list.append(row);
  }
}
function rerenderMetricAnomalies() {
  const a = $("incidentsView")._anomalies;
  if (a && current?.page === "metric" && current.line === a.lineId) drawAnomalies(a.list, a.lineId);
}

// ---- reasons (per line, or the same for every waiting line of the incident) ------------------------------------------
const pending = new Map();      // "incident|line" -> {text, bad, deadline}
const MAX_REASON = 500;
function reasonBoxes(inc, lineId) {
  const wrap = el("div", "reasons");
  const waiting = (inc.series || []).filter((s) => isWaiting(inc, s));
  const only = waiting.length === 1;
  wrap.append(reasonBox(inc.incident_id, only ? "" : lineId, only ? "Give reason (closes it)" : "Give reason for this line"));
  if (waiting.length >= 2) {
    const others = el("div", "muted others", "This anomaly also waits on: ");
    waiting.filter((s) => s.series_id !== lineId).forEach((s, i) => {
      if (i) others.append(", ");
      const a = el("a", null, tagsText(s.group_tags) || s.metric); a.href = "#/metric/" + s.series_id; others.append(a);
    });
    wrap.append(others, reasonBox(inc.incident_id, "", "Same reason for all " + waiting.length + " lines (closes it)"));
  }
  return wrap;
}
function reasonBox(incidentId, line, label) {
  const key = incidentId + "|" + line;
  const box = el("form", "reason-box");
  const input = el("textarea", "reason-in"); input.maxLength = MAX_REASON; input.rows = 1; input.placeholder = line ? "Why did this line change?" : "Why did this happen?";
  input.value = drafts.get(key) || "";
  input.addEventListener("input", () => drafts.set(key, input.value));
  input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); box.requestSubmit(); } });
  const send = el("button", "reason-send", label); send.type = "submit";
  const state = el("span", "reason-state", "");
  const p = pending.get(key); if (p) { state.textContent = p.text; state.classList.toggle("bad", !!p.bad); }
  box.append(input, send, state);
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
const drafts = new Map();
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
  for (const [key, p] of pending) if (p.deadline && Date.now() > p.deadline) { p.deadline = 0; p.text = "not saved: no answer from the Detector yet (send again)"; p.bad = true; rerenderMetricAnomalies(); }
}, 1000);

// ---- start -------------------------------------------------------------------------------------------------------
(async function start() {
  await loadCatalogue();
  route();
  connect();
  setInterval(async () => { const before = JSON.stringify(catalogue.sites.map((s) => s.dashboards.map((d) => d.id + d.lineCount))); await loadCatalogue();
    if (JSON.stringify(catalogue.sites.map((s) => s.dashboards.map((d) => d.id + d.lineCount))) !== before && current?.page === "region") route(); }, 60000);
})();

// ---- a synthetic region for measuring memory (add-dashboard-view task 3.4): __benchRegion(20, 40, 10) --------------
window.__benchRegion = async function (dashboards = 20, panels = 40, linesPer = 10) {
  const site = "bench.example", now = Date.now(), from = now - rangeMs(range);
  const dash = [];
  let n = 0;
  for (let d = 0; d < dashboards; d++) {
    const groups = [];
    for (let g = 0; g < 4; g++) {
      const ps = [];
      for (let p = 0; p < panels / 4; p++) {
        const lines = [];
        for (let l = 0; l < linesPer; l++) {
          const id = (n++).toString(16).padStart(16, "0");
          lines.push({ id, metric: "bench.metric", tags: { status: String(200 + l) }, unit: "ms", kind: "latency" });
          const pts = []; for (let i = 0; i < 300; i++) pts.push([from + i * (now - from) / 300, 100 + 20 * Math.sin(i / 9 + l) + (i === 150 ? 400 : 0)]);
          series.set(id, pts);
        }
        ps.push({ id: "b" + d + "g" + g + "p" + p, title: "Panel " + p, lines });
      }
      groups.push({ title: "Group " + g, index: g + 1, panels: ps });
    }
    dash.push({ id: "bench-" + d, title: "Bench dashboard " + d, site, groups, panelCount: panels, lineCount: panels * linesPer });
  }
  catalogue.sites = catalogue.sites.filter((s) => s.site !== site).concat([{ site, label: "BENCH", dashboards: dash }]);
  for (const d of dash) { dashById.set(d.id, { dashboard: d, site }); d.groups.forEach((g) => g.panels.forEach((p) => p.lines.forEach((l) => lineInfo.set(l.id, { line: l, places: [{ site, dashboard: d, group: g, panel: p }] })))); }
  const main = $("incidentsView");
  leave(); main.textContent = ""; current = { page: "bench" };
  main.append(el("h1", "page-title", "Bench region"));
  const t0 = performance.now();
  for (const d of dash) {
    const card = el("section", "dash"); card.dataset.dash = d.id;
    const body = el("div", "dash-body"); card.append(el("div", "dash-head", d.title), body); main.append(card);
    for (const g of d.groups) { const grid = el("div", "panels"); body.append(grid); for (const p of g.panels) grid.append(panelCard(p)); }
  }
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
  const firstMs = performance.now() - t0;
  let maxCharts = 0;
  for (let y = 0; y < document.body.scrollHeight; y += innerHeight) { scrollTo(0, y); await new Promise((r) => setTimeout(r, 60)); maxCharts = Math.max(maxCharts, charts.size); }
  scrollTo(0, 0);
  await new Promise((r) => setTimeout(r, 300));
  const mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null;
  return { dashboards, panels: dashboards * panels, lines: n, firstPaintMs: Math.round(firstMs), chartsAliveMax: maxCharts, chartsAliveNow: charts.size, jsHeapMB: mem };
};
