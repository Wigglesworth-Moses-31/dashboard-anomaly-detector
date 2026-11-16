# Verification: add-detector

15 Nov 2026. Build: `mvn test` green, **147 tests** (contracts 28, service-kit 6, collector 56 with 5 Datadog-only
skipped, store 12, detector 45). The Detector's database and live-data tests run with `-Dlive.db=true`.

Three ways the Detector was checked against the traffic generator's known problems (every 8 minutes for 3 minutes:
`/v1/invoices` 26% errors and latency x6, simulated billing the same; 20 s later `/v1/telemetry` and orders 12% errors
and latency x3; upstreams billing-core and telemetry-ingest follow their APIs):

| Check | What it feeds the Detector | Proves |
|---|---|---|
| Live data | The 42-minute live run as the Store kept it, all 226 lines, in time order (`LiveDataCheckTest`) | Detection and grouping on complete data |
| Kafka replay | `recordings/run-1` replayed through Kafka with the Store and Detector running | The whole pipeline: Kafka, warm start, outbox, health |
| Live traffic | The Collector polling the personal Datadog trial while the lab runs | Real timing and grouping (section below) |

## Rules on the 226 recorded lines
`RuleBookTest`: every line gets the rule a person would choose from its panel and tags; **none** falls to the
general rule: errors 55, traffic 84, latency 54, success rate 23, error rate 5, saturation 5.

## Live data (all 226 lines, 07:59-08:42)
| Group | Incidents | Notes |
|---|---|---|
| `/v1/invoices` | 5: 08:08 (found 08:09:20), 08:16, 08:24, 08:32, 08:40 | latency each time; 500 errors from 08:16 |
| `/v1/telemetry`, billing, orders, upstream billing-core, upstream telemetry-ingest, gateway | 5 each | one per problem cycle |
| Not caused by the generator | 7 | 1 `/v1/firmware` success-rate dip (about 7 requests a step), 3 Swagstore dashboard (2 are the simulated services' 500s summed; 1 a real traffic burst), 2 Swagstore page latency, 1 shipping traffic burst (20 s) |

The 08:00 problem starts 40 seconds after the recording begins: no history, so it cannot be judged (min-history 10 min).

## Kafka replay
| Measure | Result |
|---|---|
| Lines watched / on the general rule / warm-start failures | 226 / 0 / 0 |
| Incidents | 34; `/v1/invoices` 5 (08:08-08:40), billing 5, orders 5, gateway 5 |
| Detector time from reading the deciding value to the event sent | p50 19 ms, **p95 41 ms** |
| Waiting in Kafka before being read (the replay sends 42 minutes in 15 s) | p95 16.5 s: a replay backlog, not Detector time |
| Two replays after a restart | **identical line decisions** (655 line events, compared one by one); grouping differs (below) |

## Live traffic (16:41-17:16 UTC, Collector polling the personal Datadog trial)
Lab started 16:40 (Swagstore + traffic generator); lines are judged after 10 minutes of history, so the generator's
cycles at **16:56, 17:04 and 17:12** were judged. Collector: 226 lines, 4,986 polls, 0 failed.

| Group | 16:56 | 17:04 | 17:12 |
|---|---|---|---|
| `/v1/invoices` | latency + errors | latency + errors | latency + errors |
| `/v1/telemetry` | latency + errors (+ traffic down) | latency + errors | latency + errors |
| billing (simulated) | errors, error rate, latency, CPU | same | same |
| orders (simulated) | errors, error rate, latency, CPU | same | same |
| upstream billing-core | latency + errors | latency + errors | latency + errors + success rate |
| upstream telemetry-ingest | latency + errors + success rate | latency + errors | latency + errors + success rate |
| gateway (lab dashboard) | errors + latency | errors + latency | latency + errors |

**Every problem cycle gave one incident per affected group, each listing its signals: 21 of 21.**

| Measure | Result |
|---|---|
| Waiting in Kafka before the Detector reads a value (live: it keeps up) | **p95 15 ms** |
| Detector time from reading the deciding value to the event sent | p50 19 ms, p95 956 ms (includes the restart below) |
| So the spec's "within 5 s of the value arriving on Kafka" | **met** (well under 1 s); Datadog's own lag before Kafka is 20-50 s (add-collector) |
| Events on `incident-events` | 154, all unique; **30 of 30 incidents** have sequences 1..n with no gaps; outbox empty |

**Crash test (task 6.4):** at 17:05:33, during the 17:04 cycle with 11 incidents open, the Detector was killed with
`kill -9` and restarted 24 s later. Every open incident continued under **the same id** and closed normally
(`/v1/invoices` 17:04:00-17:07:00, sequence 4 before the crash, 7 at the close); no second incident was opened for
any group; no event was lost or repeated.

Not caused by the traffic generator (25 minutes):
| Incident | Verdict |
|---|---|
| upstream `hipstershop.cartservice` and `hipstershop.productcatalogservice`: silent since 16:51:30 | **Real**: the frontend's calls to these two stopped appearing on "Calls between services" at 16:51:20 while calls to ad, currency and recommendation continued (containers up; likely the calls are now tagged callee `N/A`) |
| Swagstore dashboard traffic/errors 16:51-17:05 | Real: Swagstore's load generator starting up |
| `/v1/telemetry` errors 16:51 (20 s), `/v1/devices` traffic down (20 s), `auth` errors 17:02-17:05, upstream `auth-provider` 2 x 20-30 s | Short dips on low-traffic lines; the generator's `c-3002` client sends extra 401s. Very few (6 in 25 min), all closed within 3 minutes |

## Spec scenarios (anomaly-detection)
| Scenario | Proved by | Result |
|---|---|---|
| One spike does not change normal | `WindowTest.oneSpikeDoesNotChangeNormal` (140, 135, 138, 1500, 142 -> 140) | **Pass** |
| A new line / restart with stored history | `LineWatch` min-history; replay: 226 warm starts from the Store, 0 failures | **Pass** |
| The lab skeleton dashboard / a new panel / a new dashboard | `RuleBookTest` (226 lines), `anUnknownLineIsWatchedByTheGeneralRule` | **Pass** |
| Default rules / stricter latency rule / title in another case | `DetectorSettingsTest`, `aStricterLatencyRuleNeedsThreeSteps`, `titleInDifferentCaseIsStillASuccessRate` | **Pass** |
| Latency jumps / one odd step | `latencyJumpOpensAnIncidentOnTheSecondStep`, `oneOddStepOpensNothing` | **Pass** |
| Success rate falls / small wobble / error rate rises | `successRateIsJudgedInPoints`, `errorRateRisingOpensAnIncident` | **Pass** |
| A partner stops sending / flood | `trafficIsWatchedBothWays` | **Pass** |
| From zero errors to one / a low-traffic success rate | `fromZeroErrorsToOneIsNotAnIncident`, `aLowTrafficSuccessRateDoesNotAlarmOnChance` | **Pass** |
| Upstream stops answering / rare error code / collection stopped | `anUpstreamThatStopsWhileItsDashboardContinuesIsSilent`, `aRareErrorCodeDoesNotAlarmOnSilence`, `aWholeDashboardStoppingIsACollectionProblemNotIncidents` | **Pass** |
| An error line stops reporting | `anErrorLineThatStopsReportingIsNotAnIncident` | **Pass** |
| One API, three signals / two APIs / upstream / new dashboard with `endpoint` | `latencySuccessRateAndErrorsOfOneApiAreOneIncident`, `twoApisAtOnceAreTwoIncidents`, `anUpstreamIncidentSaysUpstream`, `aNewDashboardWithAnEndpointTagIs...` | **Pass** |
| Reading an incident | titles, panels, normal and peak in the scenario tests; links to the dashboard at the incident's time | **Pass** |
| Flapping line / problem over / lines read out of order | `aFlappingLine...`, `aSpikeReadAfterItsGroupClosedReopensTheSameIncident` | **Pass** |
| Low value in a partial step | `aPartialStepIsNeverJudged` | **Pass** |
| Recording replayed fast / the same step twice | two replays: identical line decisions; `theSameStepTwiceCountsOnce` | **Pass** |
| Crash after saving | `IncidentRepositoryLiveTest`: a change and its event in one transaction, a broken batch saves nothing, an unsent event survives a restart; live after `kill -9`: 154 events, 0 lost, 0 repeated | **Pass** |
| Restart during an incident | `afterARestartTheOpenIncidentContinues...`; live `kill -9` with 11 open incidents: all continued under the same id and closed, none doubled | **Pass** |
| Old incidents | `closedIncidentsOlderThanTheRetentionAreRemoved` (3 days removed, yesterday kept, open kept) | **Pass** |
| The live run's values | `LiveDataCheckTest` | **Pass** |
| Live traffic | 21 of 21 group-cycles found at 16:56, 17:04, 17:12, `/v1/invoices` with latency and errors each time | **Pass** |
| Stricter latency rule, live (task 6.5) | Commit "latency 3 steps": the running Detector's settings went from version 1 to 2 within 6 s, same process; commit back: version 3, 2 steps | **Pass** |
| Open incident (health) | `/actuator/health` lists open incidents, lines per rule, warming, time to announce | **Pass** |

## Found and fixed while verifying (default rules tuned on the recording)
1. **False "silent" alarms (216 incidents in the first replay).** Silence compared a line with its whole dashboard,
   but `trace.*` metrics reach Datadog later than `lab.*` ones. Now a line is compared with the other lines of
   **its own panel query** (they come back in one answer), and only when the Detector has read every partition to
   its end (Kafka keeps order per partition only).
2. **Error lines going silent alarmed.** An error count that stops reporting is good news: `silence-skip-signals`.
3. **Noisy low-traffic percentages.** `/v1/firmware` has about 7 requests a step, so one failure moves its success
   rate by 14 points. Rates and traffic also need **3 times the line's usual wobble** (MAD; the mean distance when
   MAD is 0) and rates need 3 steps.
4. **Invoices 500 errors missed**: 26% of about 15 requests is 4 errors a step; the smallest change was 5. Now 3.
5. **Overlapping incidents for one group**: lines are read in time order per line only; a late line now reopens
   its group's incident if its spike started within a calm period of the close.
6. **Too many updates**: changes of reason are announced at most every 30 s of data time; the outbox sends in
   batches (p95 read-to-announce from 7.9 s to 41 ms).

## Known limits
- A full-speed replay reads Kafka partitions unevenly by minutes, so grouping can differ between replays (a late
  line's 08:16 spike can join the 08:40 incident). Line decisions do not differ. Live, lines of one answer arrive
  within seconds. A paced replay would be a Collector setting.
- The Detector judges the first value of each step; Datadog's later corrections are skipped.
- Swagstore panel "Slowest calls (p95, ms)" divides seconds by 1000 (panel formula bug); it has its own rule.
