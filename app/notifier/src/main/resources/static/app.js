// The live dashboards (add-datadog-look, redesign-dashboard): a top bar (region, dashboard picker, time, graph size), each region's
// dashboard list, each dashboard laid out from its exported JSON on a 12-column grid with its widget types, a shared
// crosshair, full-screen widgets and a side panel per metric with its anomalies and reasons. Values come from
// /dash/* at the range's auto interval (one Store call per dashboard and method); 10-second ranges also take live
// values (SSE) for the charts that exist, longer ones re-ask every interval. Charts exist only near the screen.
"use strict";
import { openStream } from "./rsocket.js";

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const store = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private window */ } } };

// ---- redesign-dashboard R2: graph sizes are page units × graph zoom × density, so browser zoom scales them too ------
const ZOOMS = [0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2];
let graphZoom = Number(store.get("graph-zoom")) || 1;
if (!ZOOMS.includes(graphZoom)) graphZoom = 1;
let density = store.get("density") === "compact" ? "compact" : "comfortable";
const zf = () => graphZoom * (density === "compact" ? 0.8 : 1);
const fpx = (n) => Math.round(n * zf());           // chart text follows the graph zoom
function applyZoom() {
  document.documentElement.style.setProperty("--z", String(zf()));
  for (const g of document.querySelectorAll(".dd-grid")) spanGrid(g);
}

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
    : a >= 100 ? numFmt1.format(v) : a >= 1 ? axisFmt2.format(v) : numFmt3.format(v);     // enough digits that ticks never repeat
  if (unit === "percent") t += "%"; else if (unit === "ms" || unit === "millisecond") t += " ms"; else if (unit === "second") t += " s";
  return t;
}
/** "api: /accounts/_id_/gateways/_mac_/security/state" → "api: /accounts/…/security/state" (full text on hover). */
function midTitle(t, max = 44) {
  t = String(t || "");
  if (t.length <= max) return t;
  const m = /^([^/]*?:\s*)?(\/?[^/]+)(\/.+)$/.exec(t);
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

// fix-backfill-faults F5: a line is named like in Datadog's legend: its tags, else its formula's alias, else the formula,
// else the metric. Aliases come from every dashboard JSON the page has loaded.
const formulaAlias = new Map();       // formula text -> alias; null when two widgets give it different aliases
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
let regions = [];                       // [{name, site}] from /settings/state
const lineInfo = new Map();             // line id -> {line, places: [{site, dashboard, group, panelId, panel}]}
const panelById = new Map();            // panel id -> {panel, dashboard, group, site}
const dashById = new Map();             // dashboard id -> {dashboard, site}
const incidents = new Map();            // incident id -> latest Incident (JSON)
const linesOfIncident = new Map();      // incident id -> [line ids]
const incidentsOfLine = new Map();      // line id -> Set(incident id)
let range = store.get("dash-range") || "1h";
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
    regions = s ? (s.regions || []).map((r) => ({ name: r.name, site: r.site, dashboards: r.dashboards || [] })) : [];
    if (s && s.old && s.old.site && !regions.length) regions = [{ name: "default", site: s.old.site, dashboards: [] }];
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
  drawNav();
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
    if (!inc || inc.state === "INCIDENT_STATE_CLOSED" || inc.found_in_backfill) continue;   // past (add-backfill)
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
  const m = $("incidentsView");
  if (m._redrawAnomalies && current?.page === "anomalies") m._redrawAnomalies();
  if (m._redrawWorst && current?.page === "dash") m._redrawWorst();                  // redesign-dashboard R5
}
$("attention").addEventListener("click", () => { location.hash = "#/anomalies?state=open,waiting"; });   // group-anomalies
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
      a.href = where ? "#/dashboard/" + encodeURIComponent(where.dashboard.id) + "?line=" + id : "#";
      const where = info ? info.places[0] : null;
      a.append(el("span", "al-where", where ? (siteName(where.site) + " · " + where.dashboard.title + " › " + where.panel.title) : id),
        el("span", "al-tags", info ? lineName(info.line) : ""),
        el("span", "muted", isFinite(since) ? "since " + shortTime(since) : ""));
      a.addEventListener("click", () => { list.hidden = true; });
      list.append(a);
    }
  };
  section("Open", open, "hot");
  section("Needs a reason", waiting, "warm");
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
    markAttention(); rerenderMetricAnomalies();
    return;
  }
  lastChangeAt = Date.now();
  if (staging) { const prev = staging.get(msg.incident_id); if (!prev || Number(msg.sequence) > Number(prev.sequence)) staging.set(msg.incident_id, msg); return; }
  apply(msg);
  if (msg.type === "EVENT_TYPE_OPENED" && caughtUp && !msg.incident.found_in_backfill && (msg.incident.series || []).some((s) => !s.bau)) newlyOpened.push(msg.incident);
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


// ---- charts: created near the screen, released far from it (add-dashboard-view D5) -----------------------------------
const charts = new Map();             // chart element -> {chart, spec}
const series = new Map();             // line id -> [[t, v], ...] at the dashboard's interval (shared by every widget of the line)
let intervalMs = 20000;               // the auto interval of the shown range (add-datadog-look K8)
const dark = () => matchMedia("(prefers-color-scheme: dark)").matches;
// Palettes named like the dashboard JSON's (values are our own approximations)
const PALETTES = {
  // redesign-dashboard R3: colour-blind-safe (Okabe-Ito based), distinct for 10 lines
  dog_classic: ["#3c78d8", "#e69f00", "#009e73", "#cc79a7", "#56b4e9", "#d55e00", "#7f63b8", "#8c8c2c", "#1b9e9e", "#a0522d"],
  classic: ["#3c78d8", "#e69f00", "#009e73", "#cc79a7", "#56b4e9", "#d55e00", "#7f63b8", "#8c8c2c", "#1b9e9e", "#a0522d"],
  cool: ["#1f5fa8", "#2f87c8", "#3fa7a5", "#58b8d8", "#7aa6e8", "#4c6fc4", "#2c9a8c", "#6bc0e0"],
  warm: ["#d4544b", "#e6743c", "#e6a23c", "#c9483f", "#f0c060", "#b8432e", "#e88a5a", "#d06a2c"],
  purple: ["#6a3fb0", "#8e6bbf", "#a988d6", "#5a3196", "#c3a8e8", "#7b55b5"],
  orange: ["#d07c3a", "#e6943c", "#f0b060", "#b8642a", "#f5c88a", "#c86f2a"],
  gray: ["#5f6670", "#8a8f98", "#adb2ba", "#4a5058", "#c7cbd1", "#757b84"],
  green: ["#3d8b3d", "#6aa84f", "#8fc46f", "#2f6f2f", "#b3dc93"],
  red: ["#c0392b", "#d4544b", "#e57a72", "#a12f24", "#f0a09a"],
  blue: ["#1f5fa8", "#3c77c6", "#6b9bd8", "#174a85", "#9cbde8"],
};
const palette = (name) => PALETTES[name] || PALETTES.dog_classic;

const near = new IntersectionObserver((entries) => { for (const e of entries) if (e.isIntersecting) createChart(e.target); }, { rootMargin: "300px" });
const far = new IntersectionObserver((entries) => { for (const e of entries) if (!e.isIntersecting) releaseChart(e.target); }, { rootMargin: "2000px" });
function watchChart(box, spec) { box._spec = spec; near.observe(box); far.observe(box); }
function unwatchAll(root) { for (const box of root.querySelectorAll(".chart")) { near.unobserve(box); far.unobserve(box); releaseChart(box); } }

function axisColors() { return dark() ? { axis: "#8d95a6", grid: "#2a2f3a" } : { axis: "#6b7385", grid: "#eceef2" }; }

/** A timeseries widget (lines, bars or areas), or a full-screen / side-panel graph. */
function timeseriesOption(spec) {
  const now = Date.now(), from = now - rangeMs(range);
  const all = spec.kind === "small" ? topLines(spec.lines, catalogue.lines_per_panel || 20) : spec.lines;
  const legendShown = spec.kind === "small" && spec.showLegend !== false && all.length > 1 && all.length <= 10;
  // redesign-dashboard R6: with a legend, unselected lines stay listed; without one, only the chosen lines are drawn
  const lines = spec.only && !legendShown ? all.filter((l) => spec.only.has(l)) : all;
  const unit = lineInfo.get(lines[0] || all[0])?.line.unit;
  const pal = palette(spec.palette);
  const areas = shadingFor(spec.lines, from);
  const display = spec.display || "line";
  spec._names = new Map();
  const s = lines.map((id) => {
    const info = lineInfo.get(id), i = all.indexOf(id);
    const name = info ? lineName(info.line) : id;
    spec._names.set(name, id);
    return {
      id, name, type: display === "bars" ? "bar" : "line",
      showSymbol: false, data: series.get(id) || [], color: pal[i % pal.length], animation: false, barMaxWidth: 8,
      stack: display === "bars" || display === "area" ? "s" : undefined,
      areaStyle: display === "area" ? { opacity: 0.35 } : undefined,
      lineStyle: { width: spec.kind === "metric" || spec.kind === "big" ? 2 : 1.5 }, emphasis: { focus: "series" },
      markArea: id === lines[0] && areas.length ? { silent: false, data: areas, label: { show: false } } : undefined,
    };
  });
  const c = axisColors(), f = fpx(11);
  const legend = !legendShown ? undefined
    : { show: true, bottom: 0, type: "scroll", textStyle: { color: c.axis, fontSize: f }, itemHeight: 6, itemWidth: 12, pageIconSize: 9,
      selected: Object.fromEntries(s.map((x) => [x.name, !spec.only || spec.only.has(x.id)])) };
  return {
    animation: false, useUTC: true,
    grid: { left: 6, right: 12, top: 10, bottom: legend ? f * 2 + 22 : 6, containLabel: true },
    tooltip: { trigger: "axis", confine: true, order: "valueDesc", valueFormatter: (v) => withUnit(v, unit), textStyle: { fontSize: fpx(12) }, axisPointer: { type: "line", lineStyle: { color: c.axis } } },
    legend,
    xAxis: { type: "time", min: from, max: now, splitNumber: 4, axisLabel: { color: c.axis, hideOverlap: true, fontSize: f }, axisLine: { lineStyle: { color: c.grid } }, splitLine: { show: false } },
    yAxis: { type: "value", scale: spec.yScale !== "zero", min: spec.yMin, max: spec.yMax, splitNumber: 3,
      axisLabel: { color: c.axis, fontSize: f, hideOverlap: true, formatter: (v) => axisNum(v, unit) }, splitLine: { lineStyle: { color: c.grid } } },
    series: s,
  };
}
function topLines(lines, n) {
  if (lines.length <= n) return lines;
  const peak = (id) => { let m = -Infinity; for (const p of series.get(id) || []) if (p[1] > m) m = p[1]; return m; };
  return [...lines].sort((a, b) => peak(b) - peak(a)).slice(0, n);
}
const lastValue = (id) => { const s = series.get(id) || []; return s.length ? s[s.length - 1][1] : null; };

/** Shaded anomaly times: red where open or waiting, grey for past, BAU and closed; overlaps merged. */
function shadingFor(lines, from) {
  const spans = { live: [], past: [] }, seen = new Set(), names = { live: [], past: [] };
  for (const l of lines) {
    for (const id of incidentsOfLine.get(l) || []) {
      if (seen.has(id)) continue; seen.add(id);
      const inc = incidents.get(id); if (!inc) continue;
      const start = Math.max(from, Number(inc.started_ms)), end = Number(inc.ended_ms) || Date.now();
      if (end < from) continue;
      const allBau = (inc.series || []).every((s) => s.bau);
      const kind = inc.state !== "INCIDENT_STATE_CLOSED" && !allBau && !inc.found_in_backfill ? "live" : "past";
      spans[kind].push([start, end]); names[kind].push(inc.title);
    }
  }
  const out = [];
  for (const kind of ["past", "live"]) {
    const s = spans[kind].sort((a, b) => a[0] - b[0]), merged = [];
    for (const x of s) { const last = merged[merged.length - 1]; if (last && x[0] <= last[1]) last[1] = Math.max(last[1], x[1]); else merged.push([x[0], x[1]]); }
    const label = names[kind].length === 1 ? names[kind][0] : names[kind].length + " anomalies";
    for (const [a, b] of merged) out.push([{ name: label, xAxis: a, itemStyle: { color: kind === "live" ? "rgba(217,58,58,0.09)" : "rgba(138,145,158,0.10)" } }, { xAxis: b }]);
  }
  return out;
}

function createChart(box) {
  if (charts.has(box) || !box.isConnected || !box._spec) return;
  const spec = box._spec;
  const chart = echarts.init(box, null, { renderer: "canvas" });
  if (!box._hoverWired) {                       // like Datadog: the tooltip only on the graph under the pointer
    box.addEventListener("mouseenter", () => box.classList.add("hovering"));
    box.addEventListener("mouseleave", () => box.classList.remove("hovering"));
    box._hoverWired = true;
  }
  if (spec.kind === "small" || spec.kind === "split") {                               // shared crosshair (K5)
    chart.group = "dash";
    if (!window.__ddConnected) { echarts.connect("dash"); window.__ddConnected = true; }
  }
  chart.setOption(spec.option ? spec.option() : timeseriesOption(spec));
  charts.set(box, { chart, spec });
  sizeWatch.observe(box);                                                            // R2: zoom and relayout redraw at once
  if (!spec.option) {
    box.addEventListener("mousedown", (e) => { box._mod = e.metaKey || e.ctrlKey; }, true);
    chart.on("legendselectchanged", (p) => pickLine(box, spec._names && spec._names.get(p.name), box._mod));
  }
  chart.on("click", (p) => { const id = p.seriesId || (p.componentType === "markArea" && spec.lines[0]); if (id && lineInfo.has(id)) openLine(id); });
  liveChanged();
}
function releaseChart(box) { const c = charts.get(box); if (!c) return; sizeWatch.unobserve(box); c.chart.dispose(); charts.delete(box); liveChanged(); }
const sizeWatch = new ResizeObserver((entries) => { for (const e of entries) { const c = charts.get(e.target); if (c) c.chart.resize(); } });
/** R6: a click shows only that line (again: all); Ctrl/Cmd-click adds or removes one. */
function pickLine(box, id, add) {
  const c = charts.get(box);
  if (!c || !id) return;
  const spec = c.spec, only = new Set(spec.only || []);
  if (add) { if (only.has(id)) only.delete(id); else { if (!only.size) spec.lines.forEach((l) => only.add(l)); only.has(id) ? only.delete(id) : only.add(id); } }
  else if (only.size === 1 && only.has(id)) only.clear();
  else { only.clear(); only.add(id); }
  spec.only = only.size && only.size < spec.lines.length ? only : null;
  c.chart.setOption(timeseriesOption(spec), { replaceMerge: ["series"] });
  if (box._onPick) box._onPick();
}
function redraw(filter) {
  for (const [box, c] of charts) if (!filter || c.spec.lines.some((l) => filter.has(l))) c.chart.setOption(c.spec.option ? c.spec.option() : timeseriesOption(c.spec), { replaceMerge: ["series"] });
  for (const w of document.querySelectorAll("[data-values]")) if (!filter || w._lines.some((l) => filter.has(l))) w._draw();
}
function refreshShading() { redraw(null); }
// R2: browser zoom keeps the widget you were looking at in view (charts resize by themselves, see sizeWatch)
let viewAnchor = null;
function noteAnchor() {
  const a = [...document.querySelectorAll("#incidentsView .widget, #incidentsView .dd-group-head")].find((w) => w.getBoundingClientRect().bottom > 80);
  viewAnchor = a ? { el: a, top: a.getBoundingClientRect().top } : null;
}
let anchorFrame = 0;
window.addEventListener("scroll", () => { if (!anchorFrame) anchorFrame = requestAnimationFrame(() => { anchorFrame = 0; noteAnchor(); }); }, { passive: true });
window.addEventListener("resize", () => {
  const a = viewAnchor;
  if (a && a.el.isConnected) requestAnimationFrame(() => requestAnimationFrame(() => { window.scrollBy(0, a.el.getBoundingClientRect().top - a.top); noteAnchor(); }));
});
function keepInView(change) {
  noteAnchor();
  const a = viewAnchor;
  change();
  redraw(null);
  if (a && a.el.isConnected) requestAnimationFrame(() => requestAnimationFrame(() => { window.scrollBy(0, a.el.getBoundingClientRect().top - a.top); noteAnchor(); }));
}

// ---- time: picker, auto interval, live at every range with pause (K8) ---------------------------------------------------
let paused = store.get("dash-paused") === "1";
const RANGE_LABEL = { "15m": "Past 15 Minutes", "30m": "Past 30 Minutes", "1h": "Past 1 Hour", "3h": "Past 3 Hours", "6h": "Past 6 Hours", "12h": "Past 12 Hours", "1d": "Past 1 Day" };
const autoInterval = (r) => { for (const i of [10e3, 20e3, 30e3, 60e3, 120e3, 300e3, 600e3]) if (rangeMs(r) / i <= 300) return i; return 600e3; };
const intervalText = (ms) => ms < 60e3 ? ms / 1000 + "s" : ms / 60e3 + "m";

function timeBar(onChange) {
  const box = el("div", "timebar");
  const sel = el("select", "time-select");
  for (const r of catalogue.ranges) { const o = el("option", null, RANGE_LABEL[r] || r); o.value = r; o.selected = r === range; sel.append(o); }
  sel.addEventListener("change", () => { range = sel.value; store.set("dash-range", range); onChange(); });
  const live = el("button", "live-btn" + (paused ? " paused" : ""), paused ? "▶ Paused" : "❚❚ Live");
  live.type = "button"; live.title = paused ? "Resume live updates" : "Pause live updates";
  live.addEventListener("click", () => { paused = !paused; store.set("dash-paused", paused ? "1" : "0"); onChange(); });
  box.append(el("span", "interval-chip", intervalText(autoInterval(range)) + " interval"), sel, live);
  return box;
}

// live: 10-second intervals take each new value (SSE); longer ones re-ask the range every interval (at least every 30 s)
let liveSource = null, liveKey = "", liveTimer = 0, refreshTimer = 0, refreshFn = null;
function liveChanged() { clearTimeout(liveTimer); liveTimer = setTimeout(openLive, 500); }
function openLive() {
  const lines = new Set();
  if (!paused && autoInterval(range) <= 10e3) for (const c of charts.values()) c.spec.lines.forEach((l) => lines.add(l));
  for (const w of document.querySelectorAll("[data-values]")) if (!paused && autoInterval(range) <= 10e3) w._lines.forEach((l) => lines.add(l));
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
function scheduleRefresh(fn) {
  clearTimeout(refreshTimer); refreshFn = fn;
  if (paused || !fn || autoInterval(range) <= 10e3) return;
  refreshTimer = setTimeout(async () => { try { await fn(); redraw(null); } catch (e) { /* next time */ } scheduleRefresh(fn); }, Math.max(30e3, autoInterval(range)));
}

async function loadRange(url, lines) {
  const data = await getJson(url);
  intervalMs = data.interval_ms || autoInterval(range);
  for (const [id, pts] of Object.entries(data.lines || {})) series.set(id, pts);
  for (const l of lines) if (!data.lines || !data.lines[l]) series.set(l, []);
  return data;
}

// ---- navigation (K2) -----------------------------------------------------------------------------------------------------
function siteTabs() {
  const seen = new Map();
  for (const r of regions) seen.set(r.site, r.name.toUpperCase());
  for (const s of catalogue.sites) if (!seen.has(s.site)) seen.set(s.site, s.label || s.site);
  return [...seen.entries()].map(([site, label]) => ({ site, label }));
}
const siteName = (site) => (siteTabs().find((t) => t.site === site) || { label: site }).label;
function drawNav() {
  const nav = $("regionNav");
  nav.textContent = "";
  const route = parseRoute();
  const site = route.site || (route.dash && dashById.get(route.dash)?.site) || store.get("dash-site") || siteTabs()[0]?.site;
  if (site) {                                                     // redesign-dashboard R4: regions moved to the top bar
    const a = el("a", "nav-item" + (route.page === "list" || route.page === "dash" ? " active" : ""));
    a.href = "#/dashboards/" + encodeURIComponent(site);
    a.title = "Dashboards";
    a.append(el("span", "nav-icon", "▦"), el("span", "nav-label", "Dashboards"));
    nav.append(a);
  }
  $("tabSettings").classList.toggle("active", route.page === "settings");
  $("tabAnomalies").classList.toggle("active", route.page === "anomalies");
}

// ---- redesign-dashboard R4: the top bar (region, dashboard picker, breadcrumb, time, view size, catch-up) ----------------
function drawTopBar() {
  const bar = $("topbar");
  if (!bar) return;
  bar.textContent = "";
  const r = current || parseRoute();
  const info = r.dash ? dashById.get(r.dash) : null;
  const site = r.site || info?.site || store.get("dash-site") || siteTabs()[0]?.site;
  if (site) store.set("dash-site", site);
  const seg = el("div", "seg");
  for (const t of siteTabs()) {
    const a = el("a", "seg-item" + (t.site === site && r.page !== "anomalies" && r.page !== "settings" ? " on" : ""), t.label);
    a.href = "#/dashboards/" + encodeURIComponent(t.site); a.title = "Dashboards in " + t.label + " (" + t.site + ")";
    seg.append(a);
  }
  bar.append(seg, el("span", "tb-sep", "›"), dashPicker(site, r.dash));
  if (r.page === "dash" && r.widget) {
    const box = [...document.querySelectorAll(".widget")].find((b) => b._widget && String(b._widget.w.id || b._widget.panel?.id) === r.widget);
    const group = box && box.closest(".dd-group");
    const crumbs = el("span", "crumbs");
    if (group) crumbs.append(el("span", "tb-sep", "›"), el("span", null, group.querySelector(".dd-group-head span:nth-child(2)")?.textContent || ""));
    if (box) crumbs.append(el("span", "tb-sep", "›"), el("span", null, box.querySelector(".w-title")?.textContent || ""));
    bar.append(crumbs);
  }
  bar.append(el("span", "tb-space"));
  const note = el("span", "catchup"); note.id = "catchUp"; note.textContent = catchUpText; note.hidden = !catchUpText;
  bar.append(note);
  if (r.page === "dash") bar.append(timeBar(() => route()), viewControls());
}
function dashPicker(site, dashId) {
  const wrap = el("div", "picker");
  const input = el("input", "picker-input"); input.type = "search";
  const d = dashId && dashById.get(dashId)?.dashboard;
  input.placeholder = d ? d.title : "Find a dashboard";
  input.title = "Type to find a dashboard";
  const list = el("div", "picker-list"); list.hidden = true;
  const fill = () => {
    list.textContent = "";
    const q = input.value.trim().toLowerCase(), sx = catalogue.sites.find((x) => x.site === site);
    for (const x of (sx ? sx.dashboards : []).filter((x) => !q || x.title.toLowerCase().includes(q))) {
      const a = el("a", "picker-item" + (x.id === dashId ? " on" : "")); a.href = "#/dashboard/" + encodeURIComponent(x.id);
      a.append(el("span", "picker-name", x.title));
      const c = counts(x);
      if (c.open) a.append(el("span", "pill-s st-open", c.open + " open"));
      else if (c.waiting) a.append(el("span", "pill-s st-waiting", c.waiting + " need a reason"));
      a.addEventListener("mousedown", (e) => e.preventDefault());                     // keep focus until the click lands
      a.addEventListener("click", () => input.blur());
      list.append(a);
    }
    if (!list.children.length) list.append(el("div", "picker-empty", "No dashboard matches"));
  };
  input.addEventListener("focus", () => { fill(); list.hidden = false; });
  input.addEventListener("input", fill);
  input.addEventListener("blur", () => setTimeout(() => { list.hidden = true; input.value = ""; }, 120));
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter") { const a = list.querySelector("a"); if (a) location.hash = a.getAttribute("href"); input.blur(); }
    if (e.key === "Escape") input.blur();
  });
  wrap.append(input, list);
  return wrap;
}
function viewControls() {
  const box = el("div", "view-ctl");
  const dens = el("button", "ctl-btn", density === "compact" ? "Compact" : "Comfortable");
  dens.type = "button"; dens.title = "Switch between comfortable and compact graph sizes";
  dens.addEventListener("click", () => keepInView(() => {
    density = density === "compact" ? "comfortable" : "compact"; store.set("density", density); applyZoom();
    dens.textContent = density === "compact" ? "Compact" : "Comfortable";
  }));
  const minus = el("button", "ctl-btn", "−"), pct = el("span", "ctl-pct", Math.round(graphZoom * 100) + "%"), plus = el("button", "ctl-btn", "+");
  minus.type = plus.type = "button"; minus.title = "Smaller graphs"; plus.title = "Bigger graphs";
  pct.title = "Graph size (browser zoom works too)";
  const step = (d) => keepInView(() => {
    graphZoom = ZOOMS[Math.max(0, Math.min(ZOOMS.length - 1, ZOOMS.indexOf(graphZoom) + d))];
    store.set("graph-zoom", String(graphZoom)); applyZoom(); pct.textContent = Math.round(graphZoom * 100) + "%";
  });
  minus.addEventListener("click", () => step(-1)); plus.addEventListener("click", () => step(1));
  const zoom = el("div", "zoom-ctl"); zoom.append(minus, pct, plus);
  box.append(dens, zoom);
  return box;
}
// R1: "Catching up 9 h · 40%" while the Collector fills a gap (from its health, through the Settings proxy)
let catchUpText = "", catchTimer = 0;
async function pollCatchUp() {
  clearTimeout(catchTimer);
  let text = "";
  try {
    const h = await getJson("/settings/collector");
    const d = (h.components && h.components.collector && h.components.collector.details) || {};
    const since = Object.values(d["backfill-since"] || {}).map(Number).filter(isFinite);
    const pcts = Object.values(d.backfill || {}).map((x) => parseInt(x, 10)).filter(isFinite);
    if (since.length) text = "Catching up " + duration(Date.now() - Math.min(...since)) + " · " + (pcts.length ? Math.round(pcts.reduce((a, b) => a + b, 0) / pcts.length) : 0) + "%";
  } catch (e) { /* the note simply stays away */ }
  catchUpText = text;
  const n = $("catchUp"); if (n) { n.textContent = text; n.hidden = !text; }
  catchTimer = setTimeout(pollCatchUp, text ? 5000 : 60000);
}
function parseRoute() {
  const h = location.hash;
  if (h === "#settings") return { page: "settings" };
  if (h.startsWith("#/anomalies")) return { page: "anomalies" };
  const [path, query] = h.split("?");
  const q = new URLSearchParams(query || "");
  let m;
  if ((m = /^#\/dashboards\/(.+)$/.exec(path))) return { page: "list", site: decodeURIComponent(m[1]) };
  if ((m = /^#\/dashboard\/(.+)$/.exec(path))) return { page: "dash", dash: decodeURIComponent(m[1]), widget: q.get("widget"), line: q.get("line") };
  return { page: "home" };
}
function setQuery(key, value) {
  const [path, query] = location.hash.split("?");
  const q = new URLSearchParams(query || "");
  if (value == null) q.delete(key); else q.set(key, value);
  const s = q.toString();
  location.hash = path + (s ? "?" + s : "");
}
function openLine(id) { setQuery("line", id); }

let current = null;
async function route() {
  const r = parseRoute();
  drawNav();
  if (r.page === "settings") { leave(); current = r; drawTopBar(); return; }
  if (r.page === "home") { const first = siteTabs()[0]; if (first) { location.replace("#/dashboards/" + encodeURIComponent(first.site)); return; } }
  // the same dashboard with another overlay: only the overlays change
  if (current && r.page === "dash" && current.page === "dash" && current.dash === r.dash) { current = r; drawOverlays(); drawTopBar(); return; }
  leave();
  const main = $("incidentsView");
  main.textContent = "";
  current = r;
  drawTopBar();
  if (r.page === "list") drawList(main, r.site);
  else if (r.page === "anomalies") drawAnomaliesPage(main);
  else if (r.page === "dash") { await drawDashboard(main, r.dash); drawOverlays(); if (r.widget) drawTopBar(); }
  else main.append(el("div", "empty", catalogue.problem ? "Dashboards unavailable: " + catalogue.problem : "No dashboards yet: add a region and its dashboards in Settings."));
}
function leave() { unwatchAll(document); series.clear(); liveChanged(); scheduleRefresh(null); closeOverlays(); current = null; }
window.addEventListener("hashchange", route);

// ---- the dashboard list of a region ---------------------------------------------------------------------------------------
function dashLines(d) { const out = new Set(); d.groups.forEach((g) => g.panels.forEach((p) => p.lines.forEach((l) => out.add(l.id)))); return out; }
function counts(d) { let open = 0, waiting = 0; for (const l of dashLines(d)) { const st = lineState(l); if (st === "open") open++; else if (st === "waiting") waiting++; } return { open, waiting }; }
function drawList(main, site) {
  const s = catalogue.sites.find((x) => x.site === site);
  const head = el("div", "page-head");
  head.append(el("h1", "page-title", "Dashboards"), el("span", "muted", siteName(site) + " · " + site));
  const search = el("input", "list-search"); search.type = "search"; search.placeholder = "Search dashboards";
  search.value = store.get("dash-search") || "";
  main.append(head, search);
  const table = el("div", "dash-list");
  main.append(table);
  const draw = () => {
    store.set("dash-search", search.value);
    table.textContent = "";
    const q = search.value.trim().toLowerCase();
    const hr = el("div", "dl-row dl-head"); hr.append(el("span", null, "Name"), el("span", null, "Widgets"), el("span", null, "Anomalies")); table.append(hr);
    const list = (s ? s.dashboards : []).filter((d) => !q || d.title.toLowerCase().includes(q));
    for (const d of list) {
      const a = el("a", "dl-row"); a.href = "#/dashboard/" + encodeURIComponent(d.id); a.dataset.dash = d.id;
      const c = counts(d), badges = el("span", "dl-badges");
      if (c.open) badges.append(el("span", "badge hot", "● " + c.open + " open"));
      if (c.waiting) badges.append(el("span", "badge warm", "● " + c.waiting + " need" + (c.waiting === 1 ? "s" : "") + " a reason"));
      a.append(el("span", "dl-title", d.title), el("span", "muted", String(d.panelCount)), badges);
      table.append(a);
    }
    if (!list.length) table.append(el("div", "empty", q ? "No dashboard matches." : "No dashboards collected for this region yet."));
  };
  search.addEventListener("input", draw);
  draw();
  main._redrawList = draw;
}
function refreshBadges() {
  const m = $("incidentsView");
  if (m._redrawList && current?.page === "list") m._redrawList();
  for (const w of document.querySelectorAll(".widget[data-lines]")) if (w._dot) w._dot.className = "w-dot " + widgetState(w._lines);
  for (const g of document.querySelectorAll(".dd-group")) if (g._count) {               // R5: anomalies per group, also when folded
    const ids = new Set();
    for (const w of g.querySelectorAll(".widget[data-lines]")) for (const l of w._lines) for (const id of incidentsOfLine.get(l) || []) {
      const inc = incidents.get(id); if (inc && (anomalyState(inc) === "open" || anomalyState(inc) === "waiting")) ids.add(id);
    }
    g._count.textContent = ids.size ? ids.size + (ids.size === 1 ? " anomaly" : " anomalies") : "";
    g._count.hidden = !ids.size;
  }
}
/** R3: the dot by a widget's title: open, waiting (needs a reason), past (had one), or none. */
function widgetState(lines) {
  let st = "";
  for (const l of lines) {
    const x = lineState(l);
    if (x === "open") return "open";
    if (x === "waiting") st = "waiting";
    else if (!st && (incidentsOfLine.get(l) || new Set()).size) st = "past";
  }
  return st;
}

// ---- the dashboard: JSON layout, widgets, crosshair (K3, K4, K5) ---------------------------------------------------------
const jsonCache = new Map();          // dashboard id -> {json, entry} | null
async function dashboardJson(dash) {
  if (jsonCache.has(dash.id)) return jsonCache.get(dash.id);
  const viewId = dash.id.slice(dash.id.indexOf("~") + 1), baseId = viewId.split("#")[0];
  const region = regions.find((r) => r.site === dash.site);
  let found = null;
  for (const entry of (region && region.dashboards) || []) {
    try {
      const r = await fetch("/settings/files/" + entry.file, { cache: "no-store" });
      if (!r.ok) continue;
      const json = await r.json();
      if (json.id === baseId || json.title === dash.title.split(" · ")[0]) { found = { json, entry }; break; }
    } catch (e) { /* not this one */ }
  }
  jsonCache.set(dash.id, found);
  return found;
}

/** JSON widget → catalogue panel: by widget id, else by group and title. */
function panelFor(dash, widget, groupTitle) {
  const id = Number(widget.id || 0);
  const title = (widget.definition && widget.definition.title) || "";
  let byTitle = null;
  for (const g of dash.groups) for (const p of g.panels) {
    if (id && p.widgetId === id) return p;
    if (!byTitle && p.title === title && (g.title === groupTitle || !groupTitle || !g.title)) byTitle = p;
  }
  return byTitle;
}

let gridUnit = 72;
function sizeGrid() {
  const g = document.querySelector(".dd-grid");
  if (!g) return;
  // redesign-dashboard R2: rows are page units (--row = 72px × zoom in CSS), never a share of the window
}

async function drawDashboard(main, dashId) {
  const info = dashById.get(dashId);
  if (!info) { main.append(el("div", "empty", "This dashboard is not in the catalogue (yet).")); return; }
  const dash = info.dashboard;
  const doc = await dashboardJson(dash);
  if (current?.dash !== dashId) return;
  // top bar: title, variables, time
  const top = el("div", "dash-top");
  const titleBox = el("div", "dash-title-box");
  titleBox.append(el("h1", "page-title", dash.title));
  top.append(titleBox);                                             // time, size and region moved to the top bar (R4)
  main.append(top);
  const vars = el("div", "var-bar");
  if (doc) {
    const preset = dashId.includes("#") ? dashId.slice(dashId.indexOf("#") + 1) : null;
    const pv = preset ? ((doc.json.template_variable_presets || []).find((p) => p.name === preset) || {}).template_variables || [] : [];
    for (const v of doc.json.template_variables || []) {
      const fromFilter = doc.entry.filters && doc.entry.filters[v.name];
      const fromPreset = pv.find((x) => x.name === v.name);
      const value = fromFilter || (fromPreset && (fromPreset.values ? fromPreset.values.join(", ") : fromPreset.value)) || (v.defaults && v.defaults.length ? v.defaults.join(", ") : v.default) || "*";
      const chip = el("span", "var-chip"); chip.append(el("span", "var-name", v.name), el("span", "var-value", value)); vars.append(chip);
    }
  }
  vars.append(el("span", "muted var-note", "Each 10-second value is that step's highest, so averages can be a little above Datadog's own."));
  main.append(vars);
  const worst = el("section", "worst"); main.append(worst);          // redesign-dashboard R5
  main._redrawWorst = () => drawWorst(worst, dash);
  main._redrawWorst();
  const status = el("div", "muted", "loading…"); main.append(status);
  const lines = [...dashLines(dash)];
  const load = () => loadRange("/dash/range?dashboard=" + encodeURIComponent(dash.id) + "&range=" + range, lines);
  try { await load(); status.remove(); }
  catch (e) { status.textContent = "history unavailable (" + e.message + "); live values and anomalies still arrive"; status.className = "s-warn"; }
  if (current?.dash !== dashId) return;
  const grid = el("div", "dd-grid");
  main.append(grid);
  sizeGrid();
  if (doc) noteAliases(doc.json.widgets);
  if (doc) layoutWidgets(grid, doc.json.widgets || [], dash, "", doc.json.layout_type === "free");
  else for (const g of dash.groups) {                               // no JSON (old form): groups in order, timeseries
    const box = groupBox({ definition: { title: g.title || "Panels", type: "group" } }, null);
    for (const p of g.panels) box.inner.append(widgetBox({ id: p.widgetId, definition: { type: "timeseries", title: p.title } }, p, null));
    grid.append(box.el);
  }
  sizeGrid();
  scheduleRefresh(load);
  liveChanged();
}

/**
 * Places widgets in JSON order on a grid of page-unit columns (redesign-dashboard R2): 180 px × zoom each, as many as
 * fit; a widget keeps its JSON share of the row but never gets narrower than its type allows. Heights are rows of
 * 72 px × zoom. So browser zoom and the graph zoom both make graphs bigger with fewer per row.
 */
function layoutWidgets(grid, widgets, dash, groupTitle) {
  const hasLayout = widgets.some((w) => w.layout);
  const items = [...widgets].sort((a, b) => hasLayout && a.layout && b.layout ? (a.layout.y - b.layout.y) || (a.layout.x - b.layout.x) : 0);
  for (const w of items) {
    const def = w.definition || {};
    let node;
    if (def.type === "group") {
      const box = groupBox(w, dash);
      layoutWidgets(box.inner, def.widgets || [], dash, def.title || "");
      node = box.el;
    } else node = widgetBox(w, panelFor(dash, w, groupTitle), dash);
    const L = w.layout || defaultSize(def.type);
    node._L = L; node._type = def.type;
    // never squashed: a graph gets at least 3 rows (about 230 px × zoom), a number or note 2
    const h = Math.max(L.height, def.type === "query_value" || def.type === "note" ? 2 : 3);
    if (def.type !== "group" && def.type !== "split_group") node.style.height = "calc(var(--row) * " + h + " + " + (h - 1) * 8 + "px)";
    grid.append(node);
  }
  spanGrid(grid);
  gridWatch.observe(grid);
}
const COLUMN_PX = 180, GAP_PX = 8, JSON_COLUMN_PX = 110;   // one of Datadog's 12 columns ≈ 110 px × zoom
function spanGrid(grid) {
  if (!grid.clientWidth) return;
  const cols = Math.max(1, Math.floor((grid.clientWidth + GAP_PX) / (COLUMN_PX * zf() + GAP_PX)));
  for (const node of grid.children) {
    if (!node._L) continue;
    if (node._type === "group" || node._type === "split_group") { node.style.gridColumn = "1 / -1"; continue; }
    const min = node._type === "query_value" || node._type === "note" ? 1 : 2;
    // a fixed size per JSON column (not a share of the window), so zooming out makes widgets smaller
    const want = Math.round((node._L.width * JSON_COLUMN_PX + GAP_PX) / (COLUMN_PX + GAP_PX));
    node.style.gridColumn = "span " + Math.min(cols, Math.max(min, want));
  }
}
const gridWatch = new ResizeObserver((entries) => { for (const e of entries) spanGrid(e.target); });
function defaultSize(type) {
  return ({ group: { width: 12, height: 4 }, query_value: { width: 2, height: 2 }, note: { width: 2, height: 2 }, toplist: { width: 4, height: 2 },
    query_table: { width: 4, height: 3 }, sunburst: { width: 4, height: 3 }, split_group: { width: 12, height: 3 } })[type] || { width: 4, height: 2 };
}

const GROUP_COLORS = { vivid_blue: "#3c77c6", blue: "#5b8fd6", vivid_purple: "#7b55b5", purple: "#9a7cc9", vivid_pink: "#c94f8e", pink: "#d98bb4",
  vivid_orange: "#e07a2c", orange: "#e6a05a", vivid_yellow: "#d9b43a", yellow: "#e8cc6a", vivid_green: "#4c9a4c", green: "#79b979", gray: "#8a8f98", white: "transparent" };
function groupBox(w, dash) {
  const def = w.definition || {};
  const box = el("section", "dd-group");
  const color = GROUP_COLORS[def.background_color] || "transparent";
  const head = el("button", "dd-group-head");
  head.type = "button";
  const key = "grp:" + (dash ? dash.id : "") + "|" + (def.title || "");
  const open = store.get(key) !== "0";
  const count = el("span", "grp-count"); count.hidden = true;
  head.append(el("span", "chev", open ? "▾" : "▸"), el("span", null, def.title || "Group"), count);
  box._count = count;
  head.style.setProperty("--group-color", color);
  if (def.show_title === false) head.classList.add("quiet");
  const inner = el("div", "dd-grid dd-inner");
  inner.hidden = !open;
  head.addEventListener("click", () => {
    inner.hidden = !inner.hidden; store.set(key, inner.hidden ? "0" : "1");
    head.firstChild.textContent = inner.hidden ? "▸" : "▾";
    if (inner.hidden) unwatchAll(inner); else for (const b of inner.querySelectorAll(".chart")) { near.observe(b); far.observe(b); }
  });
  box.append(head, inner);
  return { el: box, inner };
}

/** One widget: a frame with its title and menu, and its body drawn by type (K4). */
function widgetBox(w, panel, dash) {
  const def = w.definition || {};
  const type = def.type === "split_group" ? "split_group" : def.type;
  const box = el("div", "widget w-" + (type || "unknown"));
  const lines = panel ? panel.lines.map((l) => l.id) : [];
  box._lines = lines; box.dataset.lines = String(lines.length);
  const head = el("div", "w-head");
  const title = def.title || (def.source_widget_definition && def.source_widget_definition.title) || "";
  const dot = el("span", "w-dot " + widgetState(lines)); box._dot = dot;
  const tt = el("span", "w-title", title); tt.title = title;
  head.append(dot, tt);
  if (panel || type === "note") {
    const full = el("button", "w-menu", "⤢"); full.type = "button"; full.title = "View in full screen";
    full.addEventListener("click", () => setQuery("widget", String(w.id || panel?.id)));
    head.append(full);
  }
  box.append(head);
  box._widget = { w, panel, dash };
  const body = el("div", "w-body");
  box.append(body);
  if (type === "note") { body.append(el("div", "w-note", def.content || "")); return box; }
  if (!panel || !lines.length) { body.append(el("div", "w-missing", panel ? "No data yet" : "not collected by this system")); box.classList.add("missing"); return box; }
  renderBody(body, type, def, lines, "small");
  return box;
}

function requestStyle(def) {
  const req = Array.isArray(def.requests) ? def.requests[0] || {} : {};
  return { palette: (req.style && req.style.palette) || "dog_classic", display: req.display_type || "line" };
}

function renderBody(body, type, def, lines, kind) {
  if (type === "query_value") return queryValue(body, def, lines);
  if (type === "toplist") return chartBody(body, lines, () => toplistOption(lines, def));
  if (type === "query_table") return tableBody(body, def, lines);
  if (type === "sunburst") return chartBody(body, lines, () => sunburstOption(lines, def));
  if (type === "split_group") return splitBody(body, def, lines);
  const st = requestStyle(def);
  const y = def.yaxis || {};
  const chart = el("div", "chart");
  body.append(chart);
  watchChart(chart, { lines, kind, palette: st.palette, display: st.display, showLegend: def.show_legend !== false,
    yMin: y.min && y.min !== "auto" ? Number(y.min) : undefined, yMax: y.max && y.max !== "auto" ? Number(y.max) : undefined, yScale: y.include_zero ? "zero" : "auto" });
}
function chartBody(body, lines, option) { const c = el("div", "chart"); body.append(c); watchChart(c, { lines, kind: "other", option }); }

function queryValue(body, def, lines) {
  const req = Array.isArray(def.requests) ? def.requests[0] || {} : {};
  const box = el("div", "qv"); box.dataset.values = "1"; box._lines = lines;
  const big = el("div", "qv-num"), trend = el("div", "chart qv-trend");
  box.append(big, trend); body.append(box);
  const unit = lineInfo.get(lines[0])?.line.unit;
  box._draw = () => {
    let v = 0, any = false;
    for (const l of lines) { const x = lastValue(l); if (x != null) { v += x; any = true; } }   // one number: the lines summed
    const precision = def.precision != null ? def.precision : 2;
    let text = "—";
    if (any) {
      let n = v, suffix = "";
      if (def.autoscale !== false) for (const [d, s] of [[1e9, "B"], [1e6, "M"], [1e3, "k"]]) if (Math.abs(v) >= d) { n = v / d; suffix = s; break; }
      text = n.toFixed(Math.max(0, Math.min(6, precision))) + suffix + (unit === "ms" ? " ms" : unit === "percent" ? "%" : "");
    }
    big.textContent = text;
    let color = "";
    for (const f of req.conditional_formats || []) {
      const val = Number(f.value), ok = { ">": v > val, ">=": v >= val, "<": v < val, "<=": v <= val }[f.comparator];
      if (any && ok) { color = /red/.test(f.palette) ? "var(--red)" : /yellow|orange/.test(f.palette) ? "var(--amber)" : /green/.test(f.palette) ? "var(--green)" : ""; break; }
    }
    if (lines.some((l) => lineState(l) === "open")) color = "var(--red)";
    big.style.color = color;
  };
  box._draw();
  watchChart(trend, { lines, kind: "other", option: () => {
    const c = axisColors();
    return { animation: false, grid: { left: 0, right: 0, top: 0, bottom: 0 }, xAxis: { type: "time", show: false }, yAxis: { type: "value", show: false, scale: true },
      series: [{ type: "line", showSymbol: false, data: sumSeries(lines), lineStyle: { width: 1, color: c.axis }, areaStyle: { opacity: 0.08, color: c.axis } }] };
  } });
}
function sumSeries(lines) {
  const m = new Map();
  for (const l of lines) for (const [t, v] of series.get(l) || []) m.set(t, (m.get(t) || 0) + v);
  return [...m.entries()].sort((a, b) => a[0] - b[0]);
}
function toplistOption(lines, def) {
  const rows = lines.map((l) => [lineName(lineInfo.get(l)?.line), lastValue(l)]).filter((r) => r[1] != null)
    .sort((a, b) => b[1] - a[1]).slice(0, 10).reverse();
  const c = axisColors(), pal = palette(requestStyle(def).palette);
  return { animation: false, grid: { left: 8, right: 48, top: 4, bottom: 4, containLabel: true },
    xAxis: { type: "value", show: false }, yAxis: { type: "category", data: rows.map((r) => r[0]), axisLabel: { color: c.axis, fontSize: 10, width: 140, overflow: "truncate" }, axisLine: { show: false }, axisTick: { show: false } },
    series: [{ type: "bar", data: rows.map((r) => r[1]), itemStyle: { color: pal[0] }, barMaxWidth: 14, label: { show: true, position: "right", color: c.axis, fontSize: 10, formatter: (p) => num(p.value) } }] };
}
function sunburstOption(lines, def) {
  const c = axisColors(), pal = palette(requestStyle(def).palette);
  const data = lines.map((l) => ({ name: lineName(lineInfo.get(l)?.line), value: Math.max(0, lastValue(l) || 0) })).filter((d) => d.value > 0);
  return { animation: false, color: pal, tooltip: { trigger: "item" }, series: [{ type: "pie", radius: ["45%", "75%"], data, label: { color: c.axis, fontSize: 10 } }] };
}
function tableBody(body, def, lines) {
  const wrap = el("div", "qt"); wrap.dataset.values = "1"; wrap._lines = lines; body.append(wrap);
  let sortDesc = true;
  wrap._draw = () => {
    wrap.textContent = "";
    const t = el("table", "legend");
    const hr = el("tr"); const th = el("th", "on", "Value " + (sortDesc ? "▾" : "▴")); th.addEventListener("click", () => { sortDesc = !sortDesc; wrap._draw(); });
    hr.append(el("th", null, "Group"), th); t.append(hr);
    const rows = lines.map((l) => ({ l, v: lastValue(l) })).sort((a, b) => sortDesc ? (b.v ?? -Infinity) - (a.v ?? -Infinity) : (a.v ?? Infinity) - (b.v ?? Infinity));
    for (const r of rows) {
      const tr = el("tr"), a = el("a", null, lineName(lineInfo.get(r.l)?.line));
      a.href = "javascript:void 0"; a.addEventListener("click", () => openLine(r.l));
      const td = el("td"); td.append(a);
      tr.append(td, el("td", "num", withUnit(r.v, lineInfo.get(r.l)?.line.unit))); t.append(tr);
    }
    wrap.append(t);
  };
  wrap._draw();
}
function splitBody(body, def, lines) {
  const dims = (def.split_config && def.split_config.split_dimensions) || [];
  const tag = dims[0] && dims[0].one_graph_per;
  const groups = new Map();
  for (const l of lines) { const v = (lineInfo.get(l)?.line.tags || {})[tag] || "—"; if (!groups.has(v)) groups.set(v, []); groups.get(v).push(l); }
  const limit = (def.split_config && def.split_config.limit) || 6;
  const grid = el("div", "split-grid");
  body.append(grid);
  const st = requestStyle(def.source_widget_definition || {});
  for (const [v, ls] of [...groups.entries()].slice(0, limit)) {
    const full = (tag ? tag + ": " : "") + v, t = el("div", "split-title", midTitle(full)); t.title = full;   // R3
    const cell = el("div", "split-cell"); cell.append(t); cell._lines = ls;
    const chart = el("div", "chart"); cell.append(chart); grid.append(cell);
    watchChart(chart, { lines: ls, kind: "split", palette: st.palette, display: st.display, showLegend: false });
  }
}

// ---- overlays: full-screen widget, side panel for a line (K6) ----------------------------------------------------------
function closeOverlays() { for (const id of ["fullScreen", "sidePanel"]) { const o = $(id); if (o) { unwatchAll(o); o.remove(); } } }
function drawOverlays() {
  const r = current;
  const fs = $("fullScreen"), sp = $("sidePanel");
  if (fs && fs.dataset.key !== String(r.widget)) { unwatchAll(fs); fs.remove(); }
  if (sp && sp.dataset.key !== String(r.line)) { unwatchAll(sp); sp.remove(); }
  if (r.widget && !$("fullScreen")) drawFullScreen(r.widget);
  if (r.line && !$("sidePanel")) drawSidePanel(r.line);
}
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape" || !current || current.page !== "dash") return;
  if (current.line) setQuery("line", null); else if (current.widget) setQuery("widget", null);
});

function drawFullScreen(key) {
  const box = [...document.querySelectorAll(".widget")].find((b) => b._widget && String(b._widget.w.id || b._widget.panel?.id) === key);
  if (!box) return;
  const { w, panel } = box._widget;
  const def = w.definition || {};
  const ov = el("div", "overlay"); ov.id = "fullScreen"; ov.dataset.key = key;
  const card = el("div", "fs-card");
  const head = el("div", "fs-head");
  const close = el("button", "w-menu", "✕"); close.type = "button"; close.addEventListener("click", () => setQuery("widget", null));
  head.append(el("h2", "fs-title", def.title || panel?.title || ""), timeBar(() => route()), close);
  card.append(head);
  ov.addEventListener("click", (e) => { if (e.target === ov) setQuery("widget", null); });
  ov.append(card); document.body.append(ov);
  if (!panel) { card.append(el("div", "w-note", def.content || "")); return; }
  const lines = panel.lines.map((l) => l.id);
  const body = el("div", "fs-body"); card.append(body);
  const type = def.type === "timeseries" || !def.type ? "timeseries" : def.type;
  let bigChart = null;
  if (type === "timeseries" || type === "split_group") {
    const chart = el("div", "chart big"); body.append(chart); bigChart = chart;
    const st = requestStyle(type === "split_group" ? def.source_widget_definition || {} : def);
    watchChart(chart, { lines, kind: "big", palette: st.palette, display: st.display });
  } else renderBody(body, type, def, lines, "big");
  // legend table: min, max, avg, value
  const search = el("input", "legend-search"); search.type = "search"; search.placeholder = "Filter lines…";
  const table = el("table", "legend"); card.append(search, table);
  let sortKey = "value";
  const stats = (id) => { const s = series.get(id) || []; if (!s.length) return { min: null, max: null, avg: null, value: null };
    let mn = Infinity, mx = -Infinity, sum = 0; for (const [, v] of s) { mn = Math.min(mn, v); mx = Math.max(mx, v); sum += v; } return { min: mn, max: mx, avg: sum / s.length, value: s[s.length - 1][1] }; };
  const draw = () => {
    table.textContent = "";
    const hr = el("tr");
    for (const [k, t] of [["name", "Line"], ["min", "Min"], ["max", "Max"], ["avg", "Avg"], ["value", "Value"]]) { const th = el("th", k === sortKey ? "on" : null, t); th.addEventListener("click", () => { sortKey = k; draw(); }); hr.append(th); }
    table.append(hr);
    const q = search.value.trim().toLowerCase();
    const rows = panel.lines.filter((l) => !q || (lineName(l) + " " + l.metric).toLowerCase().includes(q)).map((l) => ({ l, s: stats(l.id) }));
    rows.sort((a, b) => sortKey === "name" ? tagsText(a.l.tags).localeCompare(tagsText(b.l.tags)) : (b.s[sortKey] ?? -Infinity) - (a.s[sortKey] ?? -Infinity));
    for (const { l, s } of rows) {
      const shown = !bigChart || !bigChart._spec || !bigChart._spec.only || bigChart._spec.only.has(l.id);
      const tr = el("tr", shown ? null : "dim"), a = el("a", null, lineName(l)); a.href = "javascript:void 0";
      a.title = "Show only this line (Ctrl/Cmd-click: add or remove it)";
      a.addEventListener("click", (e) => { if (bigChart && charts.has(bigChart)) pickLine(bigChart, l.id, e.metaKey || e.ctrlKey); else openLine(l.id); });   // R6
      const more = el("button", "w-menu row-open", "↗"); more.type = "button"; more.title = "Open this line's details";
      more.addEventListener("click", () => openLine(l.id));
      const st = lineState(l.id); const td = el("td"); td.append(a, more); if (st === "open" || st === "waiting") td.append(el("span", "state " + st, st === "open" ? " ● open" : " ● needs a reason"));
      tr.append(td); for (const k of ["min", "max", "avg", "value"]) tr.append(el("td", "num", withUnit(s[k], l.unit)));
      table.append(tr);
    }
  };
  search.addEventListener("input", draw); draw();
  if (bigChart) bigChart._onPick = draw;
}

let sidePanelState = null;
async function drawSidePanel(lineId) {
  const info = lineInfo.get(lineId);
  const ov = el("aside", "side-panel"); ov.id = "sidePanel"; ov.dataset.key = lineId;
  document.body.append(ov);
  const head = el("div", "sp-head");
  const close = el("button", "w-menu", "✕"); close.type = "button"; close.addEventListener("click", () => setQuery("line", null));
  head.append(el("h2", "sp-title", info ? lineName(info.line) : lineId), close);
  ov.append(head);
  if (!info) { ov.append(el("div", "empty", "This metric is not in the catalogue (yet).")); return; }
  const { line, places } = info, p0 = places[0];
  if (!series.has(lineId)) { try { await loadRange("/dash/line?id=" + lineId + "&range=" + range, [lineId]); } catch (e) { /* shown empty */ } }
  const chart = el("div", "chart metric"); ov.append(chart);
  watchChart(chart, { lines: [lineId], kind: "metric", palette: "dog_classic" });
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
  ov.append(details, el("h3", "sp-sub", "Anomalies (last 24 hours)"));
  const list = el("div", "anom-list"); ov.append(list);
  sidePanelState = { list, lineId };
  await drawAnomalies(list, lineId);
}
function rerenderMetricAnomalies() {
  if (sidePanelState && $("sidePanel") && $("sidePanel").dataset.key === sidePanelState.lineId) drawAnomalies(sidePanelState.list, sidePanelState.lineId);
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
    head.append(el("span", "anom-state", r.state === "open" ? "● open" : r.state === "needs a reason" ? "● needs a reason" : r.state === "past" ? "past · found in backfill" : "closed"),
      el("span", "anom-what", (r.signal || "line") + " " + (r.direction || "") + (r.direction === "silent" ? "" : " · " + change(r))),
      el("span", "muted", "peak " + withUnit(r.peak, r.unit) + " · normal " + withUnit(r.normal, r.unit)));
    const when = el("div", "muted anom-when", shortTime(r.start) + " · " + (r.end ? "lasted " + duration(r.end - r.start) : "for " + duration(Date.now() - r.start)));
    row.append(head, when);
    if (r.state === "past") row.append(el("div", "anom-reason muted", "Found when this dashboard's last days were pulled in; no reason needed."));
    else if (r.reason) row.append(el("div", "anom-reason", "Reason: " + r.reason + (r.s && r.s.explained_ms ? " · given " + shortTime(r.s.explained_ms) : "")));
    else if (r.inc && isWaiting(r.inc, r.s)) row.append(reasonBoxes(r.inc, lineId));
    else row.append(el("div", "anom-reason muted", "Reason: —"));
    list.append(row);
  }
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
      const a = el("a", null, seriesName(s)); a.href = "javascript:void 0"; a.addEventListener("click", () => openLine(s.series_id)); others.append(a);
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


// ---- anomalies grouped by region, dashboard and service (group-anomalies) ----------------------------------------------
const STATES = [["open", "Open"], ["waiting", "Needs a reason"], ["closed", "Closed"], ["past", "Past"]];
const STATE_RANK = { open: 0, waiting: 1, closed: 2, past: 3 };

/** An anomaly's state: open, waiting (calm, a non-BAU line without a reason), closed, or past (found in backfill). */
function anomalyState(inc) {
  if (inc.found_in_backfill) return "past";
  if (inc.state === "INCIDENT_STATE_CLOSED") return "closed";
  const lines = inc.series || [];
  if (lines.length && lines.every((s) => s.bau)) return "closed";
  if (inc.state === "INCIDENT_STATE_CALM") return lines.some((s) => isWaiting(inc, s)) ? "waiting" : "closed";
  return "open";
}

/** "datadoghq.com|api=/v1/invoices" → "api /v1/invoices"; upstream tags read "upstream …"; a dashboard group "(no service tag)". */
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

/**
 * Region → dashboard → service → anomalies, filtered by state and text, then sorted: regions and dashboards by name,
 * services worst first then by name, anomalies newest first. An anomaly on two dashboards is listed under both.
 * O(anomalies × lines) to build, O(n log n) to sort.
 */
function groupAnomalies(states, text) {
  const q = (text || "").trim().toLowerCase();
  const regionsMap = new Map();
  for (const inc of incidents.values()) {
    const st = anomalyState(inc);
    if (!states.has(st)) continue;
    const service = serviceName(inc);
    const places = new Map();                                   // "site|dashboard id" → {site, dashboard}
    for (const s of inc.series || []) {
      for (const p of (lineInfo.get(s.series_id) || { places: [] }).places) places.set(p.site + "|" + p.dashboard.id, p);
    }
    if (!places.size) places.set("?|?", { site: "?", dashboard: { id: "", title: s0(inc) } });
    for (const p of places.values()) {
      const hay = (service + " " + p.dashboard.title + " " + (inc.title || "") + " " + (inc.series || []).map((s) => tagsText(s.group_tags)).join(" ")).toLowerCase();
      if (q && !hay.includes(q)) continue;
      if (!regionsMap.has(p.site)) regionsMap.set(p.site, new Map());
      const dashes = regionsMap.get(p.site);
      if (!dashes.has(p.dashboard.id)) dashes.set(p.dashboard.id, { dashboard: p.dashboard, services: new Map() });
      const svcs = dashes.get(p.dashboard.id).services;
      if (!svcs.has(service)) svcs.set(service, []);
      svcs.get(service).push({ inc, state: st });
    }
  }
  const count = (list) => ({ open: list.filter((a) => a.state === "open").length, waiting: list.filter((a) => a.state === "waiting").length, all: list.length });
  const worst = (list) => Math.min(...list.map((a) => STATE_RANK[a.state]));
  const out = [];
  for (const [site, dashes] of regionsMap) {
    const dl = [];
    for (const { dashboard, services } of dashes.values()) {
      const sl = [...services.entries()].map(([name, list]) => ({ name, list: list.sort((a, b) => Number(b.inc.started_ms) - Number(a.inc.started_ms)), c: count(list), worst: worst(list) }));
      const untagged = (n) => (n === "(no service tag)" ? 1 : 0);          // the catch-all group goes last
      sl.sort((a, b) => (a.worst - b.worst) || (untagged(a.name) - untagged(b.name)) || a.name.localeCompare(b.name));
      const all = sl.flatMap((x) => x.list);
      dl.push({ dashboard, services: sl, c: count(all) });
    }
    dl.sort((a, b) => a.dashboard.title.localeCompare(b.dashboard.title));
    out.push({ site, label: siteName(site), dashboards: dl, c: count(dl.flatMap((d) => d.services.flatMap((s) => s.list))) });
  }
  out.sort((a, b) => a.label.localeCompare(b.label));
  return out;
}
function s0(inc) { return (inc.series && inc.series[0] && inc.series[0].dashboard_title) || "Dashboard"; }

function chosenStates() {
  const q = new URLSearchParams((location.hash.split("?")[1]) || "");
  const fromUrl = q.get("state");
  const saved = store.get("anom-states");
  const list = (fromUrl || saved || "open,waiting").split(",").filter((s) => STATE_RANK[s] != null);
  return new Set(list.length ? list : ["open", "waiting"]);
}

function drawAnomaliesPage(main) {
  const head = el("div", "page-head");
  head.append(el("h1", "page-title", "Anomalies"), el("span", "muted", "last 24 hours · region › dashboard › service"));
  main.append(head);
  const bar = el("div", "anom-filters");
  const states = chosenStates();
  for (const [key, label] of STATES) {
    const b = el("button", "chip-btn" + (states.has(key) ? " on" : "") + " st-" + key, label);
    b.type = "button";
    b.addEventListener("click", () => {
      if (states.has(key)) states.delete(key); else states.add(key);
      if (!states.size) states.add(key);
      store.set("anom-states", [...states].join(","));
      b.classList.toggle("on", states.has(key));
      draw();
    });
    bar.append(b);
  }
  const search = el("input", "list-search anom-search"); search.type = "search"; search.placeholder = "Search service, dashboard or line";
  search.value = store.get("anom-search") || "";
  search.addEventListener("input", () => { store.set("anom-search", search.value); draw(); });
  bar.append(search);
  main.append(bar);
  const tree = el("div", "anom-tree");
  main.append(tree);
  const fold = (key, node, body) => {                         // collapsible level, remembered
    const k = "anom-fold:" + key, open = store.get(k) !== "0";
    body.hidden = !open; node.querySelector(".chev").textContent = open ? "▾" : "▸";
    node.addEventListener("click", () => { body.hidden = !body.hidden; store.set(k, body.hidden ? "0" : "1"); node.querySelector(".chev").textContent = body.hidden ? "▸" : "▾"; });
  };
  const counts = (c) => {
    const s = el("span", "lvl-counts");
    if (c.open) s.append(el("span", "badge hot", "● " + c.open + " open"));
    if (c.waiting) s.append(el("span", "badge warm", "● " + c.waiting + " need" + (c.waiting === 1 ? "s" : "") + " a reason"));
    s.append(el("span", "muted", c.all + " total"));
    return s;
  };
  const level = (cls, title, sub, c) => {
    const n = el("button", "lvl " + cls); n.type = "button";
    n.append(el("span", "chev", "▾"), el("span", "lvl-title", title));
    if (sub) n.append(el("span", "muted lvl-sub", sub));
    n.append(counts(c));
    return n;
  };
  function draw() {
    tree.textContent = "";
    const groups = groupAnomalies(states, search.value);
    if (!groups.length) { tree.append(el("div", "empty", "No anomaly matches.")); return; }
    for (const r of groups) {
      const rn = level("lvl-region", r.label, r.site, r.c), rb = el("div", "lvl-body");
      tree.append(rn, rb); fold("r|" + r.site, rn, rb);
      for (const d of r.dashboards) {
        const dn = level("lvl-dash", d.dashboard.title, null, d.c), db = el("div", "lvl-body");
        rb.append(dn, db); fold("d|" + r.site + "|" + d.dashboard.id, dn, db);
        for (const s of d.services) {
          const sn = level("lvl-svc", s.name, null, s.c), sb = el("div", "lvl-body");
          db.append(sn, sb); fold("s|" + d.dashboard.id + "|" + s.name, sn, sb);
          for (const { inc, state } of s.list) sb.append(anomalyRow(inc, state, d.dashboard, r.label));
        }
      }
    }
  }
  draw();
  main._redrawAnomalies = draw;
}

function anomalyRow(inc, state, dashboard, regionLabel) {
  const row = el("div", "anom st-" + state);
  const head = el("div", "anom-head");
  const label = { open: "● open", waiting: "● needs a reason", closed: "closed", past: "past · found in backfill" }[state];
  const start = Number(inc.started_ms), end = Number(inc.ended_ms);
  let title = inc.title || "";
  while (regionLabel && title.startsWith(regionLabel + " · ")) title = title.slice(regionLabel.length + 3);   // the region is the level above
  head.append(el("span", "anom-state", label), el("span", "anom-what", title),
    el("span", "muted", shortTime(start) + " · " + (end ? "lasted " + duration(end - start) : "for " + duration(Date.now() - start))));
  row.append(head);
  if (inc.reason && state !== "open" && state !== "waiting") row.append(el("div", "anom-reason muted", "Reason: " + inc.reason));
  const lines = el("div", "anom-lines");
  const widgetOf = (s) => { const i = lineInfo.get(s.series_id); const p = i && i.places.find((x) => x.dashboard.id === dashboard.id); return p ? p.panel.title : ""; };
  const sorted = [...(inc.series || [])].sort((a, b) => widgetOf(a).localeCompare(widgetOf(b)) || tagsText(a.group_tags).localeCompare(tagsText(b.group_tags)));
  for (const s of sorted) {
    const info = lineInfo.get(s.series_id);
    const onThis = !info || info.places.some((p) => p.dashboard.id === dashboard.id);
    if (!onThis) continue;
    const a = el("a", "anom-line" + (s.bau ? " bau" : ""));
    a.href = "#/dashboard/" + encodeURIComponent(dashboard.id) + "?line=" + s.series_id;
    const where = info ? info.places.find((p) => p.dashboard.id === dashboard.id) : null;
    a.append(el("span", "al-tags", (where ? where.panel.title + " · " : "") + (seriesName(s))),
      el("span", "muted", (s.signal || "line") + " " + (s.reason || "") + (s.reason === "silent" ? "" : " · " + change(s)) + (s.bau ? " · BAU" : s.explanation ? " · reason given" : "")));
    lines.append(a);
  }
  row.append(lines);
  return row;
}

// ---- redesign-dashboard R5: Worst anomalies on each dashboard -----------------------------------------------------------
/** How far a line went from normal, as "times normal"; 10 points of a percentage count as 1 ×. */
function sizeOf(s) {
  const peak = s.peak_value ?? s.peak ?? 0, normal = s.normal_value ?? s.normal ?? 0, dir = s.direction ?? s.reason;
  if (dir === "silent") return 1;
  if (s.unit === "percent" || /rate/.test(s.signal || "")) return Math.abs(peak - normal) / 10;
  if (normal <= 0) return peak > 0 ? 2 : 1;
  return peak >= normal ? peak / normal : normal / Math.max(peak, normal * 0.01);
}
const STATE_WORD = { open: "Open", waiting: "Needs a reason", closed: "Closed", past: "Past" };
/** The dashboard's anomalies in the chosen range: state first, then size × minutes, worst first. O(a log a). */
function worstOf(dash) {
  const lines = dashLines(dash), from = Date.now() - rangeMs(range), seen = new Set(), out = [];
  for (const l of lines) for (const id of incidentsOfLine.get(l) || []) {
    if (seen.has(id)) continue;
    seen.add(id);
    const inc = incidents.get(id);
    if (!inc) continue;
    const end = Number(inc.ended_ms) || Date.now(), state = anomalyState(inc);
    if (end < from && state !== "open" && state !== "waiting") continue;    // anything still needing you is always listed
    const mine = (inc.series || []).filter((x) => lines.has(x.series_id));
    if (!mine.length) continue;
    let best = mine[0], size = sizeOf(best);
    for (const x of mine) { const z = sizeOf(x); if (z > size) { best = x; size = z; } }
    const minutes = Math.max(1, (end - Number(inc.started_ms)) / 60000);
    out.push({ inc, s: best, size, minutes, state, score: size * minutes });
  }
  return out.sort((a, b) => (STATE_RANK[a.state] - STATE_RANK[b.state]) || (b.score - a.score));
}
function drawWorst(box, dash) {
  const list = worstOf(dash), all = box._all === true;
  box.textContent = "";
  const head = el("div", "worst-head");
  head.append(el("span", "worst-title", "Worst anomalies"));
  if (list.length > 5) {
    const t = el("button", "linkish", all ? "Show top 5" : "Show all " + list.length); t.type = "button";
    t.addEventListener("click", () => { box._all = !all; drawWorst(box, dash); });
    head.append(t);
  }
  box.append(head);
  if (!list.length) { box.append(el("div", "worst-none", "No anomalies in the " + (RANGE_LABEL[range] || range).toLowerCase() + ", and none waiting for a reason")); return; }
  const rows = el("div", "worst-rows");
  const hr = el("div", "worst-row worst-hr");
  for (const t of ["#", "Service", "Line", "Signal", "Size", "Lasted", "State"]) hr.append(el("span", null, t));
  rows.append(hr);
  list.slice(0, all ? list.length : 5).forEach((x, i) => {
    const r = el("button", "worst-row"); r.type = "button"; r.title = x.inc.title || "";
    const sig = [x.s.signal, x.s.direction].filter(Boolean).join(" ");
    r.append(el("span", "num", String(i + 1)), el("span", "ellip", serviceName(x.inc)), el("span", "ellip", seriesName(x.s)),
      el("span", "ellip", sig), el("span", "num", change(x.s)), el("span", "num", duration(x.minutes * 60000)), el("span", "pill-s st-" + x.state, STATE_WORD[x.state]));
    r.addEventListener("click", () => focusLine(x.s.series_id));
    rows.append(r);
  });
  box.append(rows);
}
/** Scrolls to the line's widget (unfolding its group), shows only that line there, and opens its side panel. */
function focusLine(id) {
  const box = [...document.querySelectorAll("#incidentsView .widget")].find((w) => w._lines && w._lines.includes(id));
  if (box) {
    const inner = box.closest(".dd-inner");
    if (inner && inner.hidden) inner.previousSibling.click();
    const cell = [...box.querySelectorAll(".split-cell")].find((c) => c._lines && c._lines.includes(id)) || box;
    cell.scrollIntoView({ block: "center" });
    box.classList.add("flash"); setTimeout(() => box.classList.remove("flash"), 1600);
    for (const ch of box.querySelectorAll(".chart")) {
      if (!ch._spec || ch._spec.option || !ch._spec.lines.includes(id)) continue;
      ch._spec.only = ch._spec.lines.length > 1 ? new Set([id]) : null;
      const c = charts.get(ch); if (c) c.chart.setOption(timeseriesOption(ch._spec), { replaceMerge: ["series"] });
    }
  }
  openLine(id);
}

// ---- start -------------------------------------------------------------------------------------------------------
(async function start() {
  applyZoom();
  await loadCatalogue();
  route();
  connect();
  pollCatchUp();
  setInterval(async () => { await loadCatalogue(); if (current?.page === "list") route(); }, 60000);
})();

// ---- measuring memory with a synthetic region (add-datadog-look 3.1): __benchRegion(20, 40, 10) ----------------------
window.__benchRegion = async function (dashboards = 20, widgets = 40, linesPer = 10) {
  const site = "bench.example", now = Date.now(), from = now - rangeMs(range);
  leave(); const main = $("incidentsView"); main.textContent = ""; current = { page: "bench" };
  const grid = el("div", "dd-grid"); main.append(grid); sizeGrid();
  let n = 0; const t0 = performance.now();
  for (let d = 0; d < dashboards; d++) {
    const dash = { id: "bench~" + d, title: "Bench " + d, site, groups: [] };
    const box = groupBox({ definition: { title: "Bench dashboard " + d, type: "group", background_color: "vivid_blue" } }, dash);
    for (let w = 0; w < widgets; w++) {
      const lines = [];
      for (let l = 0; l < linesPer; l++) {
        const id = (n++).toString(16).padStart(16, "0");
        const line = { id, metric: "bench.metric", tags: { status: String(200 + l) }, unit: "ms", kind: "latency" };
        lineInfo.set(id, { line, places: [{ site, dashboard: dash, group: { title: "" }, panel: { id: "p" + w, title: "W" + w } }] });
        const pts = []; for (let i = 0; i < 300; i++) pts.push([from + i * (now - from) / 300, 100 + 20 * Math.sin(i / 9 + l) + (i === 150 ? 400 : 0)]);
        series.set(id, pts); lines.push(line);
      }
      const node = widgetBox({ id: d * 1000 + w, definition: { type: "timeseries", title: "Widget " + w } }, { id: "p" + d + "_" + w, title: "W" + w, lines }, dash);
      node.style.gridColumn = "span 4"; node.style.height = "calc(var(--row) * 2 + 8px)";
      box.inner.append(node);
    }
    grid.append(box.el);
  }
  await new Promise((r) => setTimeout(r, 50));
  const firstMs = performance.now() - t0;
  let maxCharts = 0;
  for (let y = 0; y < document.body.scrollHeight; y += innerHeight) { scrollTo(0, y); await new Promise((r) => setTimeout(r, 60)); maxCharts = Math.max(maxCharts, charts.size); }
  scrollTo(0, 0); await new Promise((r) => setTimeout(r, 300));
  const mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null;
  return { dashboards, widgets: dashboards * widgets, lines: n, firstPaintMs: Math.round(firstMs), chartsAliveMax: maxCharts, chartsAliveNow: charts.size, jsHeapMB: mem };
};
