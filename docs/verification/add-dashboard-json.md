# Verification: add-dashboard-json

17 Nov 2026. Dashboards from exported JSON files only, filters from the file, saved views (presets), several values
per variable, section headings, batching and a budget guard. Live checks on the personal trial, read-only.

## Tests (no Datadog, no Docker)

| Suite | Result | What it proves |
|---|---|---|
| All modules | **234 run, 0 failed**, 23 skipped (live-only) | contracts 28, service-kit 6, config-service 1, collector 102, store 17, detector 50, notifier 12, reporter 18 |
| `DashboardJsonTest` | 7 passed | An unchanged file is parsed once, a changed one again (cache by path + SHA-256); a broken, empty or missing file, an unknown filter variable (message lists the variables) and an unknown preset (lists the presets) each refuse the whole version; the engine gets the views of exactly the checked settings without a second read; values layered defaults → preset → `filters`; 4 presets → 4 views, a preset with the same values adds no question (shared, both views listed); `team IN (web,mobile) AND ... AND NOT api:/health`; single values give the old comma form byte for byte; "API Metrics / Latencies" vs "Upstream Service Metrics / Latencies" |
| `BatchTest` | 7 passed | Whole-word renaming (`anomalies(u3_query1 / u3_query2 ...)`, `query12` untouched); packing 100 units → 40, 40, 20 formulas; series mapped back by binary search with their own index; **batched line ids identical to unbatched**; one bad question among 32 isolated by bisection, the 31 others answered, fewer calls than panels; guard: 270 units / 400 formulas → 10 calls, 15 s at 24/s, 37.5 s without batching at 8/s; empty panel listed per view with filters |
| `CheckDashboardsTest` | 1 passed | Per view and panel output, filters printed, a view with no lines → exit code 1, batched calls |
| `CollectorSettingsTest` | 22 passed | An id refused with how to export the JSON; files only under `dashboards/*.json`, no `..`; replay accepts recorded ids; `presets: all` must stand alone |
| `DashboardFilesControllerTest` | 1 passed | Config Service serves the **committed** file only (not an uncommitted edit), in a folder path with spaces; `.env`, `collector.yml` and `..` refused |
| `DashboardLinkTest` | 3 passed | No link for a view without a dashboard id; a preset view links to its dashboard |
| Replay fixture | passed | Recorded dashboards replayed: still exactly the ids published live on 15 Nov |

## Found and fixed

| Found | Fix |
|---|---|
| The real dashboard's variables default to `select`: with defaults, `service:select` matched nothing (the empty run on 16 Nov) | Presets (saved views), each its own view |
| Presets give some variables several values | `prefix IN (a,b)` in Datadog's boolean form, only when needed |
| Empty groups used as section headings made two panels "Latencies" | Section from the empty group above |
| 23 presets × 12 panels = 270 questions per 15 s | Batching (measured: 80 formulas in one call cost one call) and a budget guard |
| Live: Spring Cloud Config's plain-file endpoint answered 404 because the project path has **spaces** (it compares an encoded and a raw path); `basedir` and a symbolic link did not help (local repos are used in place; links refused) | A small Config Service endpoint `/files/dashboards/**.json` reading the committed file with JGit |
| Live: batching made 122 calls for 146 polls: each loop pass took the one or two units that had just entered the 2-second window | A batch starts only when the earliest unit is due and takes everything due in the next 2 s: **24 calls for 144 polls** |
| Old tests passed plain strings where file references are expected (erased generics: compiled, failed at run time) | Tests use file references |

## Live (personal trial, read-only)

| Task | Check | Result |
|---|---|---|
| 2.5 Several values | `api IN (/v1/invoices,/v1/orders)` and `... AND NOT http_method:get` on lab metrics (15 Nov, 08:00–08:10 UTC) | Accepted (200); `IN` keeps only the listed APIs; `NOT http_method:get` gives 0 lines, correctly, because those lab APIs only see GET |
| 5.2 Export helper | `export-dashboards.sh us w2f-pqs-ghd jrp-8af-7xd not-an-id_` | Both saved, `- file:` lines printed, the bad id refused, no key in the output |
| 7.1 Check | `check-dashboards.sh` with the two files | Now: 0 lines everywhere (lab traffic off), exit 1 with the empty views named. `--at 2026-11-15T08:10:00Z`: **12 of 12 and 24 of 24 panels returned lines, in 1 Datadog call** |
| 7.2 Same line ids | Questions planned from the JSON files vs. from the 15 Nov recording read by id | **36 = 36, identical**: every line id the same; the export carries the id, so links work for 37 of 37 views |
| Batching live | Collector running on the two files, 60 s | **24 Datadog calls for 144 panel polls**, 0 failed, 0 throttled, budget 2% |
| Empty panels live | Health with the lab traffic off | `DEGRADED`: "us: dashboard Lab: API platform ...: 12 of 12 panels return no lines (filters env:prod)" and the same for Swagstore (25 of 25) |
| 7.3 JSON-only change | `team` default `*` → `nosuchteam` inside the file, committed, no YAML change | Applied without restart (settings version 2, same process); health shows `filters team:nosuchteam, env:prod`; reverted |
| 7.4 Broken JSON | A cut-off file committed | Refused: version unchanged, `lastRejected` names `dashboards/us/w2f-pqs-ghd.json: not valid JSON`; Datadog calls continued (71 → 80) with the previous files; reverted, accepted again |
| 7.5 The real dashboard (local only, never committed) | `presets: all` through the check with a stub source | Accepted; **23 views, 270 questions, 8 Datadog calls**; `select` left in 0 of 404 queries; 187 queries use `IN (...)` |

Afterwards `config-repo` settings were restored to `ccf8d5d` (the two exported lab dashboards stay in
`dashboards/us/`, unused in replay mode) and the system stopped.
