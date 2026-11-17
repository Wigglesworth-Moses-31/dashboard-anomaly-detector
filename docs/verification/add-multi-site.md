# Verification: add-multi-site

16 Nov 2026. Several Datadog sources (for example US and EU) collected at the same time, each in its own lane.

## Tests (no Datadog, no Docker)

| Suite | Result | What it proves |
|---|---|---|
| Collector, all | 82 run, 0 failed, 5 skipped (live-only) | Including the 22 settings/keys tests and 14 multi-site tests below |
| `MultiSiteTest` | 12 passed, stable over 3 runs | EU throttled for 4 s: no EU call, US gap ≤ interval + 100 ms; EU unreachable: only EU breakers open; EU answers 1.5 s: EU in flight ≤ its bulkhead (2), US timing unchanged; EU dashboard reads blocked: US polls on time; reconciler keeps untouched lanes (same token bucket); budget 120 units @ 15 s vs 24/s = 33%; over-budget source named on health; missing application key: nothing sent to EU, health "missing: application key", no key value in health; 200 first polls never > 50 in one second; `sources` bind from YAML; the template as `setup.sh` fills it passes every validator |
| `MultiSiteReplayTest` | 2 passed | Old recording (no source folders) gives **exactly the series ids the live Collector published on 15 Nov**; a two-source recording plays both in recorded time order (k-way merge never went back in time), US ids unchanged, EU ids all different |
| Detector, all | 49 run, 0 failed, 6 skipped | Same API in US1 and EU at once: two incidents, "US1 · /v1/invoices: latency up" and "EU · ...", keys `datadoghq.com|api=...` and `datadoghq.eu|api=...`, EU links on `app.datadoghq.eu`; one site: titles unchanged; link hosts for US1, EU, US3, US5, AP1, GOV |
| Reporter, all | 18 run, 0 failed | Two sites: tables say "EU · Latencies", "US1 · Swagstore / Rate"; one site: unchanged |

## Found and fixed while building

| Found | Fix |
|---|---|
| Design M3 said to put the source into the unit key, but `AnswerTranslator` builds every line's query id from that key: all line ids would have changed | The key stays; a separate scheduling id `lane|key` separates sources (design M3 updated) |
| A second settings constructor made Spring's binder stop binding `collector.yml` (the replay fixture failed with "No value bound") | `@ConstructorBinding` on the canonical constructor |
| The Detector learned a site only when one of its lines spiked, so the first US incident was titled without "US1 ·" | Sites are learned when lines are described |
| Live (7.3): a source whose dashboards could **never** be read had 0 units, so no breaker opened and health said `UP` with no warning; it also waited the full 60-minute refresh before trying again | Health names unreadable dashboards per source (`DEGRADED` with a warning); such a source re-reads every minute (test `unreadableDashboardsAreNamedAndRetriedSoon`) |
| Dashboard reads ran on the scheduler thread (a slow EU would have frozen US polling) | Discovery per lane on its own virtual thread (design M4) |

## setup.sh (clean copy, fake keys, `--no-check --no-build`)

| Case | Result |
|---|---|
| Unattended, `SOURCES="us eu"` | `.env` `-rw-------` with `DD_US_*`, `DD_EU_*`, a generated database password; `config-repo` with both sources |
| Interactive, two sources (answers on stdin) | Same result; second source defaults to `eu` / `datadoghq.eu` |
| Older single-source variables | One source `main` |
| EU API key without application key | Refused: "the application key (DD_EU_APP_KEY) is missing; both keys are needed"; no `config-repo` |
| Two sources on one site | Refused: "site datadoghq.com is used by two sources" |

## Live (real platform, 16 Nov)

| Task | Check | Result |
|---|---|---|
| 7.1 Old settings | The running single-site `collector.yml` unchanged, replay of `run-1` | Database: 226 series before and after; Kafka `metric-series`: 226 keys, **none new, none missing**; every service UP, page 200 |
| 7.2 Two sources | `us` = `run-1`, `eu` = the same recording as `datadoghq.eu` | 72 units (36 × 2), 452 lines (226 × 2), 12,528 answers (2 × 6,264); 226 new EU ids beside the unchanged US ids; 46 incidents titled "EU · ..." with keys `datadoghq.eu|...`; 293 incident links on `app.datadoghq.eu`; the page shows "EU · get_/: latency up" with the link "EU · Swagstore: ..." beside the US incident. US incidents restored from before the upgrade keep their old titles (design M9, one-time effect) |
| 6.3 Only Datadog keys reach the Collector | Environment variable **names** of the running Collector (values never printed) | `DD_API_KEY`, `DD_APP_KEY` only; no `PLATFORM_DB_PASSWORD` (the Store has it, as a control), no `DD_SITE` |
| 7.3 Isolation | `us` live on the trial, then `eu` added live on `unreachable.invalid` (RFC 6761: never resolves, so no request leaves the machine) | US polls per minute: **144 alone, 143–144 with EU failing**, 0 US failures. First run found a gap (below); after the fix health is `DEGRADED`: "eu: 2 of 2 dashboard(s) cannot be read ... retried every minute", and EU re-reads its 2 dashboards once a minute |
| 7.4 Live settings change | EU drops a dashboard; then US drops one and gets it back; no restart | EU change: "Source 'eu': dashboards changed, re-reading them", US 146 polls in that minute; US 36 → 12 → 36 units, 0 failures, same Collector process |
| 7.5 All tests | `./mvnw test` | **213 run, 0 failed**, 23 skipped (live-only): contracts 28, service-kit 6, collector 83, store 17, detector 49, notifier 12, reporter 18 |

Afterwards `config-repo` was restored to its earlier settings with a new commit (diff to `ccf8d5d` empty) and the
system stopped.
