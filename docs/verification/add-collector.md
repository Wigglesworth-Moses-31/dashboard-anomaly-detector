# Verification: add-collector

15 Nov 2026, personal Datadog trial (`datadoghq.com`), lab dashboards `w2f-pqs-ghd` and `jrp-8af-7xd`.
Build: `mvn clean verify` green, **90 tests** (contracts 28, service-kit 6, collector 56). Five of the collector's
tests talk to the real Datadog or Kafka and are skipped unless switched on (`-Dlive.datadog=true`,
`-Dlive.kafka=true`, `-Dkafka.dump=...`, `-Dreplay.compare=...`); each was run on 15 Nov with the results below.

## Live run
- Run 1: 07:59 to 08:42 UTC (42 min), Swagstore + traffic generator on, recording switched on live at 08:00:04
  by a `config-repo` commit (no restart). Analysed from what Kafka really holds (`KafkaDumpLiveTest`, 33,087
  messages) with `tools/analyze_collector_run.py`.
- Run 2: 08:43 to 08:48 UTC (5 min) after a bug fix (below), recording on from the start, then replayed.

| Measure (run 1) | Result |
|---|---|
| Polls / failed / throttled / skipped turns / open breakers | 5,094 / 0 / 0 / 0 / 0 (30 min of samples) |
| Datadog calls | about 144 per minute, about 8% of the measured limit (300 per 10 s) |
| Metric lines | 226 (on 36 poll units for 37 panels) |
| MetricPoints messages | 32,861 in 42 min, about 775 per minute; 50,314 finished points; 0 dropped |
| Delay from step end to first message | p50 21.1 s, p95 47.5 s, max 59.0 s (Datadog's ingestion lag + 15 s polling) |
| Classification | 143 by rule, 65 by Datadog metadata, 18 unknown (formula panels no rule matches; see note) |

## Spec scenarios (metric-collection)
| Scenario | Proved by | Result |
|---|---|---|
| Panels inside groups (and split groups) | `DatadogDashboardReaderTest` on both lab dashboards: 48 of 48 metric questions, service map skipped; live: 12 + 25 panel requests read | **Pass** |
| Panel added / removed | `RefreshAndReplayTest.addedPanelsAreCollectedRemovedOnesStop...` | **Pass** |
| Dashboard filters filled from settings | `PlanningTest` (settings value, default, `*` dropped without stray commas, `$x.value`, group-by untouched); live: 36 of 36 planned requests accepted by Datadog | **Pass** |
| A 20-second spike is in at least one step | Live: 6 of 6 traffic-generator incidents caught, `/v1/invoices` p95 peaks 8.6x to 11.3x the normal 138 ms | **Pass** |
| Collector start collects the last 10 minutes | First poll of each unit uses `backfill` (10 min): `CollectorEngine.poll`; live: 687 polls and 148 lines within seconds of start | **Pass** |
| Missing step → no point | `TranslateAndClassifyTest.gapsGiveNoPoint...`; live: values compared with Datadog include no zero-filled gaps | **Pass** |
| Step still filling → partial, then complete | `TranslateAndClassifyTest` and `EmitterTest`. Live: **0 partial points**, because Datadog only returns a step after it has ended (its data arrives 10-20 s late) | **Pass in tests; does not occur with Datadog** |
| Overlapping polls send a finished step once | `EmitterTest.finishedStepIsSentOnce...`; live: 50,314 finished points from about 5,000 overlapping 2-minute polls | **Pass** |
| New line: description first | `EmitterTest.newLineSendsItsDescriptionFirst`; live: 226 descriptions for 226 lines | **Pass** |
| Unit unknown to the source → from the settings rule | `TranslateAndClassifyTest.ruleFirstThenMetadataThenUnknown` (`lab.api.latency` → latency, ms, `rule:1`) | **Pass** |
| Throttled → wait for the reset, then all panels | `ResilienceTest.a429PausesEveryCallerUntilTheReset`; live: never throttled | **Pass** |
| One broken panel does not stop the others | `ResilienceTest.aBrokenUnitOpensItsBreakerWhileTheOtherStaysOnTime` (good unit 12 of 12 on time, broken one stops after 5) + health lists open breakers by panel | **Pass** |
| Read-only toward the source | Only GET dashboard, GET metric metadata and the read-only timeseries query are coded in `DatadogSource`; nothing was created in Datadog | **Pass** |
| Record and replay: same messages without the source | Run 2: live published 3,797 messages for 209 lines; replay produced 3,797, **0 lines differ** (`ReplayLiveCompareTest`). A 3-panel slice is kept as `ReplayFixtureTest` (runs in every build) | **Pass** |

## Open scenarios from message-contracts
| Scenario | Result |
|---|---|
| A gap in the data → no point, never 0 | **Pass** (above) |
| Newest step marked partial | **Pass in tests**; with Datadog no partial step occurs (above) |

## Other checks
- Keys: no key value in any recording, test output or service log (scanned each time).
- Values: 60 of 60 collected steps identical to the same queries read directly from Datadog.
- Kafka: description and points on their topics with the line's id as key; a message failing `ContractChecks` is
  dropped, never sent (`KafkaPublisherLiveTest`, temporary topics).

## Problems found and fixed
1. **`rollup(sum)` without `.as_count()` is ten times too small** (checked on the real API). The planner never does it.
2. **Correction to the design:** at 10-second steps `rollup(max)` changes nothing; asking for 10-second steps is
   what keeps spikes visible (design K5 updated).
3. **Weak jitter:** `hashCode()` of similar keys put starts 1 ms apart; now mixed with MurmurHash3's finaliser.
4. **Switching recording on while running kept the old output** (published.jsonl stayed empty in run 1). The
   emitter's sink is now switchable (`Emitter.setSink`, tested); run 2 recorded 3,797 messages.
5. **Replay read published.jsonl as if it were an answer file** and failed; it now reads only answer files.
6. My Kafka read-back waited for "no more messages", which never happens while the Collector runs; it now reads
   up to the end positions taken at its start.

## Notes
- 18 lines are unknown: formula panels such as "Success rate % per service" (the rule says "Success Rate",
  matching is case-sensitive) and `p95 / 1000`. A rule in `config-repo/collector.yml` classifies them; no code change.
- The health page's `calls` counter restarts when settings change, because the fetcher is rebuilt.
