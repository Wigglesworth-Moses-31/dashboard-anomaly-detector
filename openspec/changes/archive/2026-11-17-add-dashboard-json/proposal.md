# Proposal: add-dashboard-json

## Why

On another computer (16 Nov 2026) the user gave a dashboard **id** and the system collected **nothing**. The
dashboard has template variables (`$env`, `$service`...). With only the id, the Collector fills each one with the
dashboard's saved default, often `*` or a value that matches nothing in that account, so every query came back
empty, and nothing said so.

The user decided (16 Nov): **onboard dashboards as exported JSON files only.** The JSON is the dashboard exactly as
the user sees it (Datadog: *Share → Export dashboard JSON*), and its template variables say which filters to use
for which service. The user can read, edit and keep it in Git like any other setting.

A real dashboard (the user's "Service Overview", rebuilt from photos on 17 Nov into a local, never-committed file)
showed three more things. Its variables default to **`select`**, so with the defaults the Collector asked for
`service:select` and got nothing: the actual cause of the empty run. The real filters live in **23 saved views**
(presets: Payments, Billing, Orders, Profiles...), and the user chose (17 Nov) to **watch all of them**. Presets give some
variables **several values** (`team: [web, mobile]`). And the dashboard uses **empty groups as section headings**
("API Metrics", "Upstream Service Metrics"), so two panels are both called "Latencies".

Two smaller needs come with it: see **before running** whether each panel returns lines with those filters, and be
**told** when a panel returns nothing instead of discovering it later.

## What Changes

| Step | In plain words |
|---|---|
| **Dashboards are JSON files** | Each source lists its dashboards as files in `config-repo/dashboards/` (for example `dashboards/us/shop.json`). The Collector reads panels and filters from the file, never from the Datadog dashboard API. **BREAKING:** dashboard ids in `collector.yml` are refused, with a message saying how to export the JSON |
| **Filters come from the JSON** | Each template variable's default in the file is the filter used (`env: prod`, `service: payments`). Set it in Datadog before exporting, or edit the file. An optional `filters:` next to the file overrides single variables without touching the export |
| **Saved views (presets)** (user, 17 Nov) | `presets: all` next to a file watches **every** saved view of the dashboard, each as its own view ("Service Overview · Payments"); `presets: [Payments, Billing]` watches only those; without `presets`, the file's defaults are used. A variable a preset does not set keeps the file's default. Re-exporting the dashboard picks up new presets |
| **Several values for one variable** | A preset value list becomes `team IN (web, mobile)` in the question, as Datadog does on its own page |
| **Section headings** | A top-level panel below an empty group is labelled with that group ("Upstream Service Metrics / Latencies"), in health, in the check and later on the Dashboards tab |
| **Many panels per Datadog call** | Measured: one request carries 80 panel formulas for the cost of one call. Due panels of a source are packed up to 40 formulas per request: all Service Overview presets take about 10 calls per 15 s instead of 270 |
| **Shared questions asked once** | Presets that lead to the same question (for example the gateway panels of Payments and Payments Worker, which share a gateway service) are asked once; the line shows under both views |
| **The dashboard's id for links** | Incident links ("open the dashboard at that moment") need the dashboard's id. It is taken from the file when the export has one; otherwise an optional `id:` next to the file gives it; without either, incidents have no dashboard link and health says so |
| **Changes by commit, live** | Replacing or editing a JSON file and committing `config-repo` reaches the Collector within seconds, like every other setting (served by the Config Service). No restart, no re-read schedule needed |
| **Checked before use** | A file that is not valid JSON, has no widgets, or whose `filters:` name a variable the dashboard does not have is refused; the previous version stays and the settings page says why |
| **A check before running** | `scripts/check-dashboards.sh` asks each panel's question once (read-only, last 15 minutes) **with the same code the Collector uses** and prints, per panel: the filled query and how many lines came back. "Latency: 3 lines ✓", "Errors: **0 lines**: `env:prod`, `service:*`" |
| **Never silent again** | While running, the Collector's health lists, per source and dashboard, the panels whose questions return **0 lines**, with the filters used. A dashboard where every panel returns 0 lines turns health `DEGRADED` |
| **Setup asks for files** | `setup.sh` asks for each source's JSON files (paths on your computer), copies them into `config-repo/dashboards/<source>/`, and runs the check |
| **Moving from ids** | A one-time helper, `scripts/export-dashboards.sh <source> <id>...`, saves a dashboard's JSON by id (read-only, needs `dashboards_read`) for people who can use the API. Everyone else exports from the Datadog page |

**The application key needs fewer rights:** without dashboard reads, only `timeseries_query` and `metrics_read`
remain (`dashboards_read` only for the optional export helper).

## How we will know it works

| Check | Expected |
|---|---|
| Same lines as before | The lab's two dashboards as JSON files (exported from the same definitions) give **the same line ids** as when they were read by id: the queries and filters are the same, and line ids never depended on how a dashboard was found |
| Filters from the file | A JSON with `env` default `prod` asks Datadog for `env:prod`; changing it to `stage` in the file and committing changes the queries within one poll interval |
| Override | `filters: { service: payments }` next to the file wins over the file's `service: *` |
| Zero lines found | A filter that matches nothing (`env: nosuchenv`): the check prints "0 lines" with the filled query; the running Collector's health names those panels and the filters, and is `DEGRADED` |
| Refused | A broken JSON file, an empty one, an unknown variable in `filters:`, and a dashboard id in place of a file are each refused with a clear message; the previous settings keep running |
| Links | With an id in the file or next to it, incident links open that dashboard; without, no link and a health note |
| Old recordings | Replay of the recordings made by id still works (they carry their own dashboard definitions) |
| All presets | The rebuilt Service Overview file with `presets: all`: 23 views, every panel's question filled per preset; shared questions merged; the budget shown per source before any 429 |
| Several values | `team: [web, mobile]` gives `team IN (web,mobile)`; the boolean form is checked against the personal trial (read-only) |
| Headings | "API Metrics / Latencies" and "Upstream Service Metrics / Latencies" are told apart |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `metric-collection`: dashboards come from JSON files in the settings (not from the dashboard API); filters from
  the file with optional overrides; the pre-run check; panels returning 0 lines are reported.
- `runtime-configuration`: dashboard JSON files are settings: versioned in `config-repo`, served by the Config
  Service, validated before use, applied without a restart.

## Out of scope

| Not in this change | Why |
|---|---|
| Choosing different `filters:` per preset | Presets already carry their filters; `filters:` applies to every view of that file |
| Noticing that a dashboard changed in Datadog after it was exported | JSON only means the file is the truth; re-export to update. A drift check would need the dashboard API again |
| Catch-up after the laptop was off | `add-catch-up`, after `add-dashboard-view` |

## Impact

- `app/collector`: dashboards from files (a parser shared with the existing dashboard reader), settings and
  validators, live reload, zero-line counting and health, the check mode, tests.
- `app/config-service`: serves the JSON files (plain text from `config-repo`, as Spring Cloud Config already can).
- `scripts/setup.sh`, new `scripts/check-dashboards.sh` and `scripts/export-dashboards.sh`,
  `config-template/collector.yml`, `config-template/dashboards/`, `README.md`.
- Line ids, contracts, topics and the database: unchanged.
