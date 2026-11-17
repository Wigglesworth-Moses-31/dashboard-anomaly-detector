# Tasks

## 1. Settings: dashboards as file references

- [x] 1.1 Add `DashboardRef(file, id, filters)` to `CollectorSettings` for both the `sources` form and the single-site form, with a `Converter<String, DashboardRef>` so an old id binds and can be refused (design J1); validators: `file` required, relative, under `dashboards/`, `.json`, no `..`; a file once per source; ids refused with the export message; `dashboards` ignored in `source: replay`; verify by tests: each rule's message, an id refused with the export hint, a replay config with ids accepted, the YAML form binds (`sources` with `- file:`, `id:`, `filters:`)

## 2. Loading the files

- [x] 2.1 Serve and fetch files (design J2): `DashboardFiles` fetches `{config-uri}/collector/default/main/{file}` in parallel (5 s timeout), parses with the existing `DatadogDashboardReader`, caches by (path, SHA-256), evicts files no longer listed; verify by tests with a local HTTP stub: parse once per content, a changed file parsed again, a missing file gives a clear error
- [x] 2.2 Accept YAML and files together (design J3): the loading validator (valid JSON, has `widgets`, `filters` names ⊆ variables with the list in the message, file id vs `id:` must agree), parsed dashboards in the accepted snapshot; `SettingsHolder` also reloads on `config.client.version`; verify by tests: a broken file refuses the whole version and keeps the old one; an unknown filter variable lists the real ones; a JSON-only change is applied
- [x] 2.3 Discovery from the snapshot (design J3): lanes plan from parsed dashboards instead of `MetricSource.dashboard(id)`; filters from the ref win over file defaults; verify by tests: `env` default `prod` → `env:prod`; `filters: {service: payments}` wins; changing the default in the file changes the questions after reload

## 2b. Presets, several values, headings, budget guard

- [x] 2.4 Presets (design J10): `presets: all | [names]` next to a file, one view per preset named "<dashboard> · <preset>", values = defaults → preset → `filters`, unknown names refused with the list; verify by tests on a fixture with presets (including one that sets only some variables) and, locally, on the rebuilt Service Overview file: 23 views, 270 distinct requests
- [x] 2.5 Several values (design J11): boolean form `prefix IN (a,b) AND ... AND NOT tag:x` only when a variable has several values; single values unchanged; verify by tests (the spec's Payments example; existing fixture questions byte-identical) and against the personal trial (read-only, one boolean query on lab metrics returns lines)
- [x] 2.6 Section headings (design J12): panels below an empty group take its title as their section; verify by tests ("API Metrics / Latencies" vs "Upstream Service Metrics / Latencies"; existing dashboards' questions unchanged)
- [x] 2.7 Budget guard (design J13): per-lane interval stretched to stay at 90% of the allowed rate, health message, DEGRADED above the recent window; verify by tests: 270 units at 24/s stays 15 s; at 8/s polls every 37.5 s with the health message

- [x] 2.8 Batching (design J14): pack due units of a lane into requests of at most `batch-formulas` (40) formulas, rename queries per unit, map series back by offset (binary search), split a rejected batch in two until the bad unit is alone, first polls in their own batch; verify by tests: answers mapped to the right units, line ids identical to unbatched, a bad unit isolated in O(log n) calls, and live on the trial: the same lines as unbatched with far fewer calls

## 3. View ids, links, empty panels

- [x] 3.1 View ids and links (design J4): file id, else `id:`, else `file:<path>`; the Detector builds no link for `file:` ids; verify by tests (Collector descriptor views; Detector incident without link) and the health note "no id for links"
- [x] 3.2 Empty panels (design J5): last line count per unit, health per source → dashboard → panel with the filled filters, `DEGRADED` when a whole dashboard is empty; verify by tests: 12 of 12 empty → DEGRADED with the exact warning; 2 of 12 → listed, UP

## 4. Replay

- [x] 4.1 Replay plays every recorded dashboard of a source, ignoring settings dashboards (design J7); verify by the existing `ReplayFixtureTest` and `MultiSiteReplayTest` (same published ids) with settings that list no dashboards

## 5. The check, the export helper, setup

- [x] 5.1 `CheckDashboards` main and `scripts/check-dashboards.sh` (design J6): working-tree settings and files, `.env` Datadog keys only, one call per unit over 15 minutes at 2 per second, output per dashboard and panel, exit 1 when a dashboard returns nothing; verify by a unit test with a stub source (output and exit code) and live on the trial (read-only)
- [x] 5.2 `scripts/export-dashboards.sh <source> <id>...` (design J8): read-only GET with a headers file, pretty JSON into `config-repo/dashboards/<source>/`, prints the `- file:` lines; verify live on the trial for the two lab dashboards, with no key in the output
- [x] 5.3 `setup.sh` asks for JSON files per source (interactive and `DASHBOARD_FILES_<NAME>`), copies them, writes `- file:` entries, runs the check; template `collector.yml` with the new form and `config-template/dashboards/README.md`; verify in a clean copy (fake keys, `--no-check`): files copied, settings valid (template test updated)

## 6. Docs

- [x] 6.1 README: onboarding by JSON (export steps with the Datadog menu path, filters, `check-dashboards.sh`, the export helper), fewer key scopes, troubleshooting "0 lines"; `docs/verification/add-dashboard-json.md`

## 7. Live checks (personal trial only, read-only)

- [x] 7.1 Export the two lab dashboards with `export-dashboards.sh`, run `check-dashboards.sh`, and record per-panel line counts
- [x] 7.2 Same line ids: run the Collector live with the two files and compare the `metric-series` keys with the 226 ids collected by id before (none new for the same filters)
- [x] 7.3 Live file change: change a variable default in one file (`env` to a value that matches nothing), commit; verify within one poll the questions change, health names the empty panels and turns DEGRADED; revert and verify recovery
- [x] 7.4 A broken JSON committed: refused, the previous dashboards keep being collected, `/actuator/settings` names the file
- [x] 7.5 The rebuilt Service Overview file (local only, never committed) through `check-dashboards.sh` with a stub source: 23 views listed, filled questions printed per preset, no `service:select` left
- [x] 7.6 Restore the original settings, run all tests, record results

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `add-dashboard-view` (design next), then `add-catch-up`.
