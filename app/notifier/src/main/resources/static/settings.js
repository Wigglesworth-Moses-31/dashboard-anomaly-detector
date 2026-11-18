// The Settings tab (add-settings-page S7): regions with their keys, and each region's dashboards (create, read,
// update, delete). It talks only to /settings/** on this page's own address; the Notifier passes it on, on this
// machine only. Keys are typed into password fields, sent once, and the fields are cleared; no answer contains a key.

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const button = (text, cls, onClick) => { const b = el("button", "s-btn " + (cls || ""), text); b.type = "button"; b.addEventListener("click", onClick); return b; };
const short = (commit) => (commit || "").slice(0, 7);

let state = null;          // GET /settings/state
let keys = [];             // GET /settings/keys
let health = {};           // Collector health: warnings per lane

// ---- tabs ------------------------------------------------------------------------------------------------------
function showTab() {
  const settings = location.hash === "#settings";
  $("incidentsView").hidden = settings;
  $("settingsView").hidden = !settings;
  $("tabSettings").classList.toggle("active", settings);
  window.dispatchEvent(new CustomEvent("viewchange"));
  if (settings) load();
}
window.addEventListener("hashchange", showTab);
showTab();

// ---- talking to the services --------------------------------------------------------------------------------------
async function call(method, path, body, ifMatch) {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (ifMatch) headers["If-Match"] = ifMatch;
  let r;
  try {
    r = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: "no-store" });
  } catch (e) { return { ok: false, problem: "the Notifier cannot be reached" }; }
  const data = await r.json().catch(() => ({}));
  return r.ok ? { ok: true, data } : { ok: false, status: r.status, problem: data.problem || "HTTP " + r.status };
}

async function load() {
  const [s, k, h] = await Promise.all([call("GET", "/settings/state"), call("GET", "/settings/keys"), call("GET", "/settings/collector")]);
  if (!s.ok) { $("sRegions").textContent = "Settings unavailable: " + s.problem; return; }
  state = s.data; keys = k.ok ? k.data : []; health = h.ok ? h.data : {};
  render();
  const running = (health.components && health.components.collector && health.components.collector.details
    && Object.keys(health.components.collector.details.backfill || {}).length) || 0;
  if (running && location.hash === "#settings") setTimeout(() => { if (location.hash === "#settings") load(); }, 5000);   // progress moves
}

function feedback(box, text, bad) {
  box.textContent = text; box.classList.toggle("bad", !!bad);
}

// ---- regions --------------------------------------------------------------------------------------------------
function render() {
  const regions = state.regions || [];
  $("sCount").textContent = regions.length + " of " + state.max;
  const notice = $("sNotice");
  notice.hidden = !(state.form === "old" || state.form === "collector.yml" || state.form === "both");
  notice.textContent = state.form === "old"
    ? "This machine's collector.yml uses the old single-site form (datadog-site with dashboards). The page can show it but not change it; set it up again with scripts/setup.sh to manage regions here."
    : state.form === "collector.yml"
      ? "The regions are still in collector.yml. The first change made here moves them to regions.yml in their own commit (nothing else in collector.yml changes)."
      : "Regions are in both collector.yml and regions.yml; regions.yml wins. Remove 'sources:' from collector.yml by hand.";
  const list = $("sRegions");
  list.textContent = "";
  for (const r of regions) list.append(regionCard(r));
  if (state.old) {
    const card = el("div", "s-card");
    card.append(el("div", "s-head"));
    card.firstChild.append(el("span", "title", "Single site (old form)"), el("span", "muted", state.old.site || ""), el("span", "muted", "read-only"));
    for (const d of state.old.dashboards || []) card.append(el("div", "s-row", d));
    list.append(card);
  } else if (!regions.length) list.append(el("div", "muted", "No region yet."));
  const add = $("sAdd");
  add.textContent = "";
  if ((state.can_add || []).length) add.append(addRegionForm(state.can_add));
}

function keyState(name) {
  const k = keys.find((x) => x.region === name);
  return k ? k.present : false;
}

function laneWarnings(name) {
  const w = (health.details && health.details.warnings) || (health.components && health.components.collector && health.components.collector.details && health.components.collector.details.warnings) || [];
  return w.filter((x) => typeof x === "string" && x.startsWith(name + ":"));
}

function regionCard(r) {
  const card = el("div", "s-card");
  const head = el("div", "s-head");
  head.append(el("span", "title", r.name.toUpperCase()), el("span", "muted", r.site),
    el("span", keyState(r.name) ? "s-ok" : "s-bad", keyState(r.name) ? "keys ✓" : "keys missing"));
  const out = el("span", "s-state");
  const actions = el("span", "s-actions");
  const keyBox = el("div");
  actions.append(
    button("Replace keys", "", () => { keyBox.textContent = ""; keyBox.append(keysForm(r.name, "Check and save keys", async (api, app, fb) => {
      feedback(fb, "checking with Datadog…");
      const k = await call("PUT", "/settings/keys/" + r.name, { api_key: api, app_key: app });
      if (!k.ok) return feedback(fb, "not saved: " + k.problem, true);
      feedback(fb, "keys saved to .env; the region uses them from its next poll"); setTimeout(load, 1500);
    })); }),
    button("Remove region", "danger", async () => {
      const files = (r.dashboards || []).map((d) => d.file);
      if (!confirm("Remove region " + r.name.toUpperCase() + "?\n\nIts entry and " + (files.length ? "these dashboard files:\n  " + files.join("\n  ") : "no dashboard files")
        + "\nare removed in one commit, and its keys from .env. Its incidents and data stay until they age out.")) return;
      feedback(out, "removing…");
      const res = await call("DELETE", "/settings/regions/" + r.name, undefined, state.commit);
      if (!res.ok) return feedback(out, "not removed: " + res.problem, true);
      const k = await call("DELETE", "/settings/keys/" + r.name);
      feedback(out, "removed (commit " + short(res.data.commit) + ")" + (k.ok ? "" : "; keys not removed from .env: " + k.problem), !k.ok);
      setTimeout(load, 800);
    }));
  head.append(actions, out);
  card.append(head);
  for (const w of laneWarnings(r.name)) card.append(el("div", "s-warn", w));
  const bf = (health.components && health.components.collector && health.components.collector.details && health.components.collector.details.backfill) || {};
  for (const [view, pct] of Object.entries(bf)) {                 // add-backfill: "Shop (datadoghq.eu)" → "45%"
    if (view.endsWith("(" + r.site + ")")) card.append(el("div", "s-progress", "Pulling the last 2 days of " + view.slice(0, view.lastIndexOf(" (")) + ": " + pct));
  }
  card.append(keyBox);
  const table = el("div", "s-dash");
  for (const d of r.dashboards || []) table.append(dashboardRow(r, d));
  if (!(r.dashboards || []).length) table.append(el("div", "muted", "No dashboards yet."));
  card.append(table, uploadBox(r, null));
  return card;
}

function keysForm(region, label, onSend) {
  const form = el("form", "s-keys");
  form.autocomplete = "off";
  const api = el("input"); api.type = "password"; api.placeholder = "API key"; api.autocomplete = "off"; api.spellcheck = false;
  const app = el("input"); app.type = "password"; app.placeholder = "Application key"; app.autocomplete = "off"; app.spellcheck = false;
  const fb = el("span", "s-state");
  const send = el("button", "s-btn primary", label); send.type = "submit";
  form.append(api, app, send, fb);
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const a = api.value.trim(), b = app.value.trim();
    api.value = ""; app.value = "";                       // sent once; never kept in the page
    if (!a || !b) return feedback(fb, "both keys are needed", true);
    send.disabled = true;
    try { await onSend(a, b, fb); } finally { send.disabled = false; }
  });
  return form;
}

function addRegionForm(available) {
  const box = el("div", "s-card s-new");
  box.append(el("div", "title", "Add a region"));
  const pick = el("select");
  for (const r of available) { const o = el("option", null, r === "us" ? "US (datadoghq.com)" : "EU (datadoghq.eu)"); o.value = r; pick.append(o); }
  box.append(pick, keysForm(null, "Check keys and add", async (api, app, fb) => {
    const region = pick.value;
    feedback(fb, "checking the keys with Datadog (read-only)…");
    const k = await call("PUT", "/settings/keys/" + region, { api_key: api, app_key: app });
    if (!k.ok) return feedback(fb, "not added: " + k.problem, true);
    feedback(fb, "keys saved; adding the region…");
    const res = await call("POST", "/settings/regions", { region }, state.commit);
    if (!res.ok) return feedback(fb, "keys saved to .env, but the region was not added: " + res.problem, true);
    feedback(fb, "added (commit " + short(res.data.commit) + "); now add its dashboards");
    setTimeout(load, 800);
  }));
  return box;
}

// ---- dashboards -------------------------------------------------------------------------------------------------
function watchedText(d) {
  const p = d.presets == null ? [] : Array.isArray(d.presets) ? d.presets : [String(d.presets)];
  if (!p.length) return "the file's default filters";
  if (p.length === 1 && String(p[0]).toLowerCase() === "all") return "all saved views";
  return p.length + " saved view" + (p.length === 1 ? "" : "s") + ": " + p.join(", ");
}

function dashboardRow(r, d) {
  const row = el("div", "s-row");
  const name = d.file.split("/").pop();
  const detail = el("div", "s-detail");
  const out = el("span", "s-state");
  const dl = el("a", "s-btn", "Download"); dl.href = "/settings/files/" + d.file; dl.download = name;
  const filters = Object.entries(d.filters || {}).map(([k, v]) => k + "=" + v).join(", ");
  row.append(el("span", "s-file", name), el("span", "muted", watchedText(d).replace("the file's default filters", filters ? "the file's defaults" : "the file's default filters")
    + (filters ? " · filters " + filters : "") + (d.id ? " · id " + d.id : "")), el("span", "s-actions"));
  row.lastChild.append(
    button("Open", "", () => openDashboard(r, d, detail)),
    button("Edit JSON", "", () => openEditor(r, d, detail)),
    dl,
    button("Replace", "", () => { detail.textContent = ""; detail.append(uploadBox(r, d)); }),
    button("Delete", "danger", async () => {
      if (!confirm("Delete " + name + " from region " + r.name.toUpperCase() + "?\n\nThe file and its entry are removed in one commit; its lines stop being collected within one poll.")) return;
      feedback(out, "deleting…");
      const res = await call("DELETE", "/settings/regions/" + r.name + "/dashboards?file=" + encodeURIComponent(d.file), undefined, state.commit);
      if (!res.ok) return feedback(out, "not deleted: " + res.problem, true);
      feedback(out, "deleted (commit " + short(res.data.commit) + ")"); setTimeout(load, 800);
    }), out);
  const wrap = el("div"); wrap.append(row, detail);
  return wrap;
}

async function openDashboard(r, d, box) {
  if (box.childElementCount) { if (!box.closeEditor || box.closeEditor()) box.textContent = ""; return; }
  box.textContent = "reading…";
  let text;
  try { const f = await fetch("/settings/files/" + d.file, { cache: "no-store" }); if (!f.ok) throw new Error("HTTP " + f.status); text = await f.text(); }
  catch (e) { box.textContent = "cannot be read: " + e.message; return; }
  const res = await call("POST", "/settings/check", { region: r.name, file_name: d.file.split("/").pop(), content: text, presets: ["all"] });
  box.textContent = "";
  if (!res.ok) { box.append(el("div", "s-warn", res.problem)); return; }
  box.append(summaryView(res.data.summary));
  box.append(viewPicker(res.data.summary.savedViews, d.presets, async (presets, fb) => {
    feedback(fb, "saving…");
    const s = await call("PATCH", "/settings/regions/" + r.name + "/dashboards", { file: d.file, presets }, state.commit);
    if (!s.ok) return feedback(fb, "not saved: " + s.problem, true);
    feedback(fb, "saved (commit " + short(s.data.commit) + ")"); setTimeout(load, 800);
  }, "Save watched views"));
}

function summaryView(s) {
  const box = el("div", "s-summary");
  box.append(el("div", "title", s.title || "(no title)"),
    el("div", "muted", s.panelCount + " panels" + (s.skippedWidgets ? " (+" + s.skippedWidgets + " widgets without metrics)" : "")
      + " · " + (s.savedViews || []).length + " saved views" + (s.id ? " · id " + s.id : "")));
  if ((s.variables || []).length) box.append(el("div", "muted", "Variables: " + s.variables.map((v) => "$" + v.name + " = " + (v.defaultValue || "*")).join(", ")));
  const groups = el("div", "s-groups");
  for (const g of s.groups || []) {
    groups.append(el("div", "s-group", (g.group || "(no group)") + " (" + g.panels.length + ")"));
    groups.append(el("div", "muted s-panels", g.panels.join(" · ")));
  }
  box.append(groups);
  return box;
}

/** Checkboxes for saved views: all ticked = every view ("all"); none = the file's own default filters. */
function viewPicker(names, current, onSave, label) {
  const box = el("div", "s-views");
  names = names || [];
  const cur = current == null ? ["all"] : Array.isArray(current) ? current : [String(current)];
  const all = cur.length === 1 && String(cur[0]).toLowerCase() === "all";
  const boxes = [];
  if (names.length) {
    box.append(el("div", "muted", "Saved views to watch (each becomes its own view; none ticked = the file's default filters):"));
    for (const n of names) {
      const l = el("label", "s-view"); const c = el("input"); c.type = "checkbox"; c.value = n; c.checked = all || cur.includes(n);
      boxes.push(c); l.append(c, document.createTextNode(" " + n)); box.append(l);
    }
  } else box.append(el("div", "muted", "No saved views: the file's default filters are used."));
  const chosen = () => { const on = boxes.filter((c) => c.checked).map((c) => c.value); return on.length === boxes.length && boxes.length ? ["all"] : on; };
  if (onSave) {
    const fb = el("span", "s-state");
    box.append(button(label, "primary", () => onSave(chosen(), fb)), fb);
  }
  box.chosen = chosen;
  return box;
}

/** Upload an export: check → (check panels) → add; with {@code current} it replaces that file. */
function uploadBox(r, current) {
  const box = el("div", "s-upload");
  const pick = el("input"); pick.type = "file"; pick.accept = ".json,application/json";
  const label = el("label", "s-btn", current ? "Choose the newer export…" : "+ Add dashboard (exported JSON)…");
  label.append(pick); pick.hidden = true;
  const area = el("div");
  box.append(label, area);
  pick.addEventListener("change", async () => {
    const file = pick.files[0]; pick.value = "";
    if (!file) return;
    if (file.size > 5_000_000) { area.textContent = "larger than 5 MB"; return; }
    const content = await file.text();
    const fileName = current ? current.file.split("/").pop() : file.name;
    area.textContent = "reading with the Collector's code…";
    const res = await call("POST", "/settings/check", { region: r.name, file_name: fileName, content, presets: ["all"], current: current ? current.file : undefined });
    area.textContent = "";
    if (!res.ok) { area.append(el("div", "s-warn", "Refused: " + res.problem)); return; }
    area.append(summaryView(res.data.summary));
    if (res.data.diff) area.append(diffView(res.data.diff));
    const picker = viewPicker(res.data.summary.savedViews, current ? current.presets : ["all"], null);
    area.append(picker);
    const lines = el("div", "s-lines");
    const fb = el("span", "s-state");
    area.append(
      button("Check panels (asks Datadog once, read-only)", "", async () => {
        feedback(fb, "asking each panel once…");
        const c = await call("POST", "/settings/check", { region: r.name, file_name: fileName, content, presets: picker.chosen(), ask: true });
        if (!c.ok) return feedback(fb, c.problem, true);
        feedback(fb, ""); lines.textContent = "";
        for (const v of c.data.lines || []) {
          const withLines = v.panels.filter((p) => p.lines > 0).length;
          lines.append(el("div", withLines ? "s-group" : "s-group s-badtext", v.view + " — " + withLines + " of " + v.panels.length + " panels returned lines"));
          for (const p of v.panels) lines.append(el("div", p.lines > 0 ? "s-pl" : "s-pl s-badtext", (p.lines > 0 ? "✓ " : p.lines === 0 ? "✗ " : "! ") + p.panel + " — " + (p.lines >= 0 ? p.lines + " lines" : p.error)));
        }
      }),
      button(current ? "Update" : "Add", "primary", async () => {
        feedback(fb, "saving…");
        const s = await call("POST", "/settings/regions/" + r.name + "/dashboards",
          { file_name: fileName, content, presets: picker.chosen(), replace: !!current }, state.commit);
        if (!s.ok) return feedback(fb, "not saved: " + s.problem, true);
        feedback(fb, "saved (commit " + short(s.data.commit) + "); collected from the next poll"); setTimeout(load, 1200);
      }), fb, lines);
  });
  return box;
}

function diffView(d) {
  const box = el("div", "s-diff");
  const line = (sign, list, what) => { if (list && list.length) box.append(el("div", sign === "+" ? "s-plus" : "s-minus", sign + list.length + " " + what + ": " + list.join(", "))); };
  line("+", d.panelsAdded, "panel" + (d.panelsAdded.length === 1 ? "" : "s"));
  line("−", d.panelsRemoved, "panel" + (d.panelsRemoved.length === 1 ? "" : "s"));
  line("+", d.viewsAdded, "saved view" + (d.viewsAdded.length === 1 ? "" : "s"));
  line("−", d.viewsRemoved, "saved view" + (d.viewsRemoved.length === 1 ? "" : "s"));
  if (!box.childElementCount) box.append(el("div", "muted", "Same panels and saved views as the current file."));
  return box;
}

// ---- the JSON editor (design S5b): the committed file as it is; mistakes shown while typing; check, then save ----
const asList = (p) => (p == null ? ["all"] : Array.isArray(p) ? p : [String(p)]);

/** "line 41 column 7" from the browser's JSON.parse message (either form it uses). */
function jsonError(text, e) {
  const msg = String(e.message || e);
  let m = /line (\d+) column (\d+)/.exec(msg);
  const clean = (s) => s.replace(/^JSON\.parse: /, "").replace(/\s*\(line \d+ column \d+\)/, "").replace(/ at line \d+ column \d+.*$/, "")
    .replace(/ in JSON at position \d+.*$/, "");
  if (m) return { line: +m[1], col: +m[2], msg: clean(msg) };
  m = /position (\d+)/.exec(msg);
  let line = 1, col = 1;
  if (m) {
    const pos = Math.min(+m[1], text.length);
    for (let i = 0; i < pos; i++) { if (text.charCodeAt(i) === 10) { line++; col = 1; } else col++; }
  }
  return { line, col, msg: clean(msg) };
}

async function openEditor(r, d, box) {
  if (box.closeEditor && !box.closeEditor()) return;
  box.textContent = "reading…";
  let original;
  try { const f = await fetch("/settings/files/" + d.file, { cache: "no-store" }); if (!f.ok) throw new Error("HTTP " + f.status); original = await f.text(); }
  catch (e) { box.textContent = "cannot be read: " + e.message; return; }
  box.textContent = "";
  const name = d.file.split("/").pop();
  const wrap = el("div", "s-editor");
  const gutter = el("pre", "s-gutter");
  const code = el("textarea", "s-code");
  code.spellcheck = false; code.wrap = "off"; code.value = original; code.setAttribute("aria-label", "JSON of " + name);
  wrap.append(gutter, code);
  const status = el("div", "s-state");
  const fb = el("span", "s-state");
  const result = el("div");
  let lines = -1, errorLine = 0, valid = true, timer = 0;

  function drawGutter() {
    const n = code.value.split("\n").length;
    if (n === lines && !gutter.dataset.dirty) return;
    lines = n; delete gutter.dataset.dirty;
    const out = [];
    for (let i = 1; i <= n; i++) out.push(i === errorLine ? "▶" + i : String(i));
    gutter.textContent = out.join("\n");
    gutter.scrollTop = code.scrollTop;
  }
  function validate() {
    const text = code.value;
    try {
      JSON.parse(text);
      valid = true; errorLine = 0;
      feedback(status, "valid JSON · " + text.split("\n").length + " lines" + (text === original ? " · no changes" : " · changed"));
    } catch (e) {
      const at = jsonError(text, e);
      valid = false; errorLine = at.line;
      feedback(status, "line " + at.line + ", column " + at.col + ": " + at.msg, true);
    }
    gutter.dataset.dirty = "1"; drawGutter();
    save.disabled = !valid || text === original;
    format.disabled = !valid;
    check.disabled = !valid;
  }
  code.addEventListener("input", () => { drawGutter(); clearTimeout(timer); timer = setTimeout(validate, 300); save.disabled = true; });
  code.addEventListener("scroll", () => { gutter.scrollTop = code.scrollTop; });
  code.addEventListener("keydown", (e) => {
    if (e.key === "Tab" && !e.shiftKey) { e.preventDefault(); code.setRangeText("  ", code.selectionStart, code.selectionEnd, "end"); code.dispatchEvent(new Event("input")); }
  });

  const format = button("Format", "", () => { try { code.value = JSON.stringify(JSON.parse(code.value), null, 2) + "\n"; validate(); } catch (e) { validate(); } });
  const undo = button("Undo all", "", () => { if (code.value === original || confirm("Put back the committed version?")) { code.value = original; validate(); } });
  const check = button("Check", "", async () => {
    feedback(fb, "reading with the Collector's code…"); result.textContent = "";
    const c = await call("POST", "/settings/check", { region: r.name, file_name: name, content: code.value, current: d.file,
      presets: asList(d.presets), filters: d.filters || {} });
    if (!c.ok) return feedback(fb, "Refused: " + c.problem, true);
    feedback(fb, "");
    result.append(diffView(c.data.diff || { panelsAdded: [], panelsRemoved: [], viewsAdded: [], viewsRemoved: [] }), summaryView(c.data.summary));
  });
  const save = button("Save", "primary", async () => {
    feedback(fb, "checking…");
    const c = await call("POST", "/settings/check", { region: r.name, file_name: name, content: code.value, current: d.file,
      presets: asList(d.presets), filters: d.filters || {} });
    if (!c.ok) return feedback(fb, "not saved: " + c.problem, true);
    feedback(fb, "saving…");
    const s = await call("POST", "/settings/regions/" + r.name + "/dashboards", { file_name: name, content: code.value, replace: true, edited: true }, state.commit);
    if (!s.ok) return feedback(fb, "not saved: " + s.problem, true);
    original = code.value; validate();
    feedback(fb, "saved (commit " + short(s.data.commit) + "); collected from the next poll");
    setTimeout(load, 1500);
  });
  const close = button("Close", "", () => { if (box.closeEditor()) box.textContent = ""; });
  box.closeEditor = () => {
    if (code.isConnected && code.value !== original && !confirm("Discard your edits to " + name + "?")) return false;
    box.closeEditor = null; return true;
  };
  const bar = el("div", "s-editbar");
  bar.append(el("span", "s-file", "Editing " + name), format, undo, check, save, close, fb);
  box.append(bar, wrap, status, result);
  drawGutter(); validate();
  code.setSelectionRange(0, 0);
  code.focus({ preventScroll: true });
  code.scrollTop = 0; gutter.scrollTop = 0;
}
