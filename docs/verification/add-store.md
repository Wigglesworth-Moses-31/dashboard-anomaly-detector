# Verification: add-store

15 Nov 2026. No Datadog: the Collector replayed `recordings/run-1` (42 minutes, `source: replay`) into Kafka,
three times in all, and Kafka also still held the live run's messages.
Build: `mvn test` green, **105 tests** (contracts 28, service-kit 6, collector 56, store 15). The Store's live
tests are skipped unless switched on: `-Dlive.db=true` (database), `-Dlive.kafka=true` (Kafka against the
database), `-Dlive.grpc=true` (the running Store over gRPC). All three were run with the results below.

## What was stored
| Measure | Result |
|---|---|
| Messages in `metric-points` | 104,815 (live run + 3 replays) |
| Distinct finished points in them | 57,690 for 226 lines, 07:59:20 to 08:48:00 UTC |
| Rows in `metrics.points` | **57,690**: 0 missing, 0 with a different value, 0 extra (`StoreMatchesKafkaLiveTest`) |
| Lines in `metrics.series` | 226 |
| Messages set aside / failed batches | 0 / 0 |
| Save delay (poll to Kafka commit), whole backlog | p50 8-11 ms, p95 18-24 ms (target: 95% within 5 s) |
| Per-minute rows (`metrics.points_1m`) | 10,335 |

## Spec scenarios (metric-storage)
| Scenario | Proved by | Result |
|---|---|---|
| The same step delivered twice | `StoreDatabaseLiveTest.storedOnce...`: one row; live: 104,815 messages, 57,690 rows = distinct points | **Pass** |
| A corrected value | `StoreDatabaseLiveTest.storedOnceCorrectedValueWins...` (5 then 6 → 6); `BatchPlannerTest` (last value wins inside a batch) | **Pass** |
| Numbers before their description | `StoreDatabaseLiveTest`: points stored with no `metrics.series` row (no foreign key) | **Pass** |
| Store stopped while numbers arrive | Tables emptied, Kafka position rewound, Store killed with `kill -9` at 15,614 rows (10,500 of 71,749 messages acknowledged), restarted: 57,690 rows, 0 missing, 0 extra, 0 wrong | **Pass** |
| Points without a series id | `BatchPlannerTest.lastValueWinsAndABrokenMessage...`: broken message between two good ones set aside with its reason (`metric-points/2@101`), both good ones kept; plus a message that is not Protobuf at all | **Pass** |
| Last hour per minute | `StoreDatabaseLiveTest.perMinuteHistoryAveragesEachMinute`; live over gRPC: 50 minute values for the invoices line, oldest first | **Pass** |
| Long raw range | `StoreDatabaseLiveTest.longRawHistory...`: 2,500 points arrive as 1000 + 1000 + 500, oldest first | **Pass** |
| The last 6 hours | `StoreDatabaseLiveTest.summaryMatches...`: 2,160 points in 06:00-12:00; count, min, max, mean and p95 equal an independent computation; a point at exactly 12:00 is excluded | **Pass** |
| Window too long | 40 days refused with `INVALID_ARGUMENT` and the reason, directly and over the network (`StoreGrpcClientLiveTest`) | **Pass** |
| Yesterday's summary in India time | `GetDaySummary(2026-11-01, Asia/Kolkata)` covers midnight to midnight India time (1,440 points of a 28-hour series) | **Pass** |
| A day equals its window | Same test: day answer = window answer (count, min, max, mean, p95) | **Pass** |
| The Reporter did not run for three days | TimescaleDB job list: `policy_retention` on `points` and on `points_1m`, once each, `drop_after 48:00:00`, plus the per-minute refresh every minute (start 3 h, end 1 min). The database itself deletes older chunks | **Pass** (policy check; a 3-day wait was not run) |
| Asking to keep data for a month | `StoreSettingsTest` (30 days, 14 days, 6 hours rejected); live: `raw-safety-limit: 30d` committed → "change rejected; still using version 1", policies stayed at 48 h | **Pass** |
| Clean-up stopped working (size alarm) | `max-database-size: 5MB` committed → within 5 s health DEGRADED: "database is 20.8 MB, over the limit; largest table metrics.points"; back to 1GB → UP within 5 s | **Pass** |
| Replay of the 42-minute recording | Store running during replays: all finished points stored once (above); save delay p95 18-24 ms | **Pass** |
| Store falls behind | Health shows `kafkaLag` per topic (watched going to 0 after the restart with 61,000 messages waiting) | **Pass** |

## Other checks
| Check | Result |
|---|---|
| Flyway migrations | V1 (tables, hypertable) and V2 (per-minute view, outside a transaction via `V2__points_per_minute.sql.conf`) applied, both `success = t` |
| Password | Passed by `scripts/system.sh` to the Store only; found in **0** files under `run/`, `config-repo/`, `app/store/src` (the 18 "password" lines in the log are Kafka's `ssl.*.password = null`) |
| `/v1/invoices` incident peaks over gRPC (task 7.2) | Normal 138 ms; 6 of 6 complete incidents, peaks 1213, 1194, 1569, 1439, 1251, 1395 ms = **8.6x to 11.3x**: the same as the Collector's live run |
| Database size (task 7.3) | Database 21 MB; `metrics.points` 9.4 MB (4.4 MB rows, 5.0 MB index) = **166 bytes per point**; per-minute 174 bytes per row. Projected: ~280 MB raw + ~57 MB per-minute a day, **~680 MB at the 2-day maximum**, under the 1 GB alarm. The design's estimate (~300 MB) was about half; design S3 now holds the measured numbers |

## Found and fixed while verifying
1. **Health counters were wrong** (38 "inserted or changed" out of 118,039, and a negative count). With batched
   inserts the database driver answers "done, count unknown" (-2) per row. The counters are now "rows written"
   (`pointsSaved`, `descriptionsSaved`); exact counts come from the database.
2. **The Store did not leave its Kafka group on a polite stop**, so Kafka waited ~45 s before handing its
   partitions to a restarted Store. `stop()` now wakes the consumer and waits for it to close: stop takes 1 s and
   the group is empty at once.
3. **The settings page showed the size limit as `{"negative": false}`**. Settings are now shown field by field,
   sizes as text (`1073741824B`).
4. **Per-minute answers for old minutes**: the refresh policy summarises only the last 3 hours, so points that
   arrive more than 3 hours late are missing from per-minute answers (raw answers have them). The Collector only
   sends the last few minutes, so this does not happen in practice; noted in design Risks.
