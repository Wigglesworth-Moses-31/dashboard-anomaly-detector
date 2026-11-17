# Tasks

## 1. Settings and keys

- [x] 1.1 Add `sources` to `CollectorSettings` (design M5): `Source` record (name, site, api-key-env, app-key-env, dashboards, dashboard-filters, optional max-concurrent-requests and rate-limit-share), the old single-site form mapped to one source `default` with `DD_API_KEY` / `DD_APP_KEY`, and validators (unique names of 1–12 lower-case letters or digits, unique sites, key names matching `^DD_([A-Z0-9]+_)?(API|APP)_KEY$`, a dashboard once per source, not both `sources` and `datadog-site`); verify by tests: old settings give one `default` source, duplicate name, duplicate site, `PLATFORM_DB_PASSWORD` as a key name and both forms at once are each refused with a message naming the problem
- [x] 1.2 Read keys by name (design M6): `DatadogCredentials.fromEnvironment(env, apiName, appName)` reporting present / missing per key, never the value; verify by tests: both present, API key only ("application key" missing), none, and that `toString` and the startup log never contain a value

## 2. Lanes in the Collector

- [x] 2.1 Add `Lane` and `LaneConfig` (design M1): credentials, `DatadogSource` for its site, its own `Fetcher` (token bucket, 429 gate, fair bulkhead), `Classifier` with its own metadata cache, `AnswerTranslator` for its site, its unit key set and counters; a lane with missing keys sends nothing; verify by unit tests with stub sources
- [x] 2.2 Put the lane in unit keys (design M3): `PollUnit.lane`, key `<lane>|<question>`, `PollPlan` merging only within one lane; verify by test: the same question on two panels of one source is one unit, in two sources two units
- [x] 2.3 Move discovery off the scheduler thread (design M4): each lane discovers on a virtual thread and hands its plan to the engine, which replaces only that lane's units; verify by test: a lane whose dashboard reads block for 60 s does not delay the other lane's due polls by more than 100 ms
- [x] 2.4 Reconcile settings changes by lane (design M7): keep unchanged lanes (bucket, gate, breakers, metadata, units), re-discover lanes whose dashboards or filters changed, rebuild lanes whose site, keys, bulkhead or share changed, add and drop by name; verify by tests for each row of the M7 table, including that an unchanged lane keeps its token bucket state
- [x] 2.5 Add the budget check (design M8) and verify by test: 120 units at 15 s against 300 per 10 s × 0.8 gives budget 0.33; 360 units against a reported limit of 100 per 10 s gives a warning naming the lane
- [x] 2.6 Isolation tests (spec "A source's trouble stays in that source"), with two stub sources and a test clock: EU throttled for 30 s → no EU call for 30 s and US poll delays unchanged; EU unreachable → EU breakers open, every US unit polled on time; EU answers taking 10 s → EU in flight never above its bulkhead, US in flight and timing unchanged
- [x] 2.7 Spread test (spec "Sources do not burst together"): 2 lanes × 100 units at 15 s → no one second of the first interval holds more than a quarter of first polls
- [x] 2.8 Per-source health (design M11): a `sources` block per lane (keys, dashboards, units, budget, calls, failed, throttled, in flight, gate, open breakers, freshness), `WARNING` for over budget, missing keys or open breakers, never `DOWN` for one lane; verify by test and on the live health page

## 3. Recording and replay

- [x] 3.1 Record per source into `recordings/<run>/<source>/` and replay all sources with a k-way merge in recorded time order (design M10); an old folder without source sub-folders replays as `default`; verify by tests: the existing replay fixture still gives the same messages and ids, and a two-source fixture (the US fixture copied as `eu` on `datadoghq.eu`) gives both sets of lines with different ids, in time order

## 4. Detector

- [x] 4.1 Dashboard links for every site (design M9) and verify by test: `datadoghq.eu` → `https://app.datadoghq.eu/dashboard/...`, `datadoghq.com` → `app.datadoghq.com`, `us3.datadoghq.com` stays, `ddog-gov.com` → `app.ddog-gov.com`
- [x] 4.2 Site in group keys, and site labels in titles once two or more sites are seen (design M9); verify by tests: `/v1/invoices` latency up in US1 and EU at once gives two incidents titled "US1 · ..." and "EU · ...", and with one site titles are unchanged

## 5. Notifier and Reporter

- [x] 5.1 Show the site label where a dashboard is named on the page and in the report when lines of two or more sites are present; verify by the existing Notifier and Reporter tests plus one two-site case each

## 6. Setup, scripts, settings template and docs

- [x] 6.1 `config-template/collector.yml`: `sources:` with one source filled by setup and a commented EU example; `.env.example` with `DD_US_*` / `DD_EU_*` examples; verify the template passes the settings validators
- [x] 6.2 `scripts/setup.sh`: ask for one or more sources (name, site, both keys, dashboard ids), check each pair read-only, refuse to finish when either key of a source is missing, write `.env` and `sources:`; keep the unattended mode (`SOURCES="us eu"`, `DD_<NAME>_*`, `DASHBOARDS_<NAME>`); verify in a clean copy with one source and with two (the second checked with `--no-check`)
- [x] 6.3 `scripts/system.sh`: pass the Collector only `.env` names matching the key pattern; verify the Collector's process environment holds the Datadog keys and not `PLATFORM_DB_PASSWORD` (`ps eww` on the Collector's pid, checking names only, never printing values)
- [x] 6.4 README: two-region setup, the settings table, health per source, troubleshooting (keys missing, budget warning); `docs/verification/add-multi-site.md` started

## 7. Live checks

- [x] 7.1 Old settings, live: the running single-site `collector.yml` unchanged → same line ids as before (compare `metric-series` keys before and after), Detector warm start and the page work
- [x] 7.2 Two sources: `us` live on the trial plus `eu` as a replay of the US recording presented as `datadoghq.eu` (design M10); verify both in health, different line ids, EU links, "EU ·" titles on the page
- [x] 7.3 Isolation on the real system: point `eu` at an unreachable site for 5 minutes and verify US poll delays and incident timing do not change, EU breakers open, and health is `WARNING` naming `eu`
- [x] 7.4 A settings change without restart: add and then remove a dashboard of one source; verify the other source's polls are not reset (its `calls` keep growing at the same rate)
- [x] 7.5 Run all tests (`./mvnw test`) and record the results in `docs/verification/add-multi-site.md`

## Workflow follow-up

- The user reviews and explains the change back, then it is archived.
- Then `add-dashboard-view` (specs done; design next), then `add-compression`.
