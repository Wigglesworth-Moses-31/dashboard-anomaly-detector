# Tasks

## 1. Settings

- [x] 1.1 Add the new settings of design K13 to `CollectorSettings` with validators, and to `config-repo/collector.yml`, and verify by tests that bad values (negative concurrency, rate-limit share above 1, unknown kind in a rule) are rejected
- [x] 1.2 Verify the Collector starts with the new settings and shows them on `/actuator/settings`

## 2. Source port and Datadog adapter

- [x] 2.1 Add the `MetricSource` port and its result types (dashboard, timeseries answer, metadata) and verify they compile with no Datadog types leaking out of the adapter package
- [x] 2.2 Add `DatadogSource` (Java `HttpClient`, keys only in headers, rate-limit headers read) and verify against the trial that it reads one dashboard, one timeseries answer and one metadata record, with no key in any log
- [x] 2.3 Add `RecordingSource` (decorator) and verify that a recorded file holds the request and answer but no key

## 3. Discovery and query planning

- [x] 3.1 Add the dashboard walker (iterative depth-first) and verify on the two lab dashboards' recorded JSON that it finds all 48 metric queries and skips the service map
- [x] 3.2 Check with the real API how `.rollup(sum, 10)` combines with `.as_count()` (and `.rollup(max, 10)` with plain queries), record the answer in the design, and verify the planner produces requests Datadog accepts
- [x] 3.3 Add filter filling (design K4) and verify by tests: value from settings, dashboard default, `*` dropped without stray commas, `$name.value` form
- [x] 3.4 Add the poll plan with duplicate merging and verify that one request used on two panels becomes one unit with two views

## 4. Answers to messages

- [x] 4.1 Add the answer translator (design K6) and verify by tests: `null` steps give no point, the unfinished step is partial, group tags become the line's tags, the series id matches `SeriesIds`
- [x] 4.2 Add the classifier (design K8) with the LRU metadata cache and verify by tests: rule first, metadata second, unknown last, and `classified_by` names the layer
- [x] 4.3 Add the emitter (design K7) and verify by tests: a new line's description comes first, a finished step is sent once, a changed value is sent again, partial steps are always sent, memory per line stays bounded
- [x] 4.4 Add the Kafka publisher (idempotent, keys from `Topics`, `ContractChecks` before sending) and verify against the local Kafka that a description and its points arrive on the right topics with the line's id as key

## 5. Scheduling and resilience

- [x] 5.1 Add the scheduler (min-heap, jitter, skip-if-running) and verify by a test with a fake clock that each unit runs once per interval and starts are spread
- [x] 5.2 Add the token bucket, the 429 gate and the bulkhead, and verify by tests with a fake source that limits are respected and a 429 pauses all requests until its reset
- [x] 5.3 Add retries with backoff and full jitter and the per-unit circuit breaker, and verify by tests that a broken unit opens its breaker while other units stay on time
- [x] 5.4 Add collector details to the health page (last successful poll, open breakers by panel, rate-limit state) and verify them on `/actuator/health`

## 6. Discovery refresh and replay

- [x] 6.1 Re-read dashboards on schedule and on settings change, and verify by a test that an added panel is collected and a removed one stops
- [x] 6.2 Add `ReplaySource` with a replay clock and verify that replaying a recording publishes the same messages as the live run that made it

## 7. Live verification on the trial

- [x] 7.1 Start the lab traffic and the system, run the Collector against both lab dashboards for at least 30 minutes with recording on, and verify messages flow with no `ContractChecks` failure
- [x] 7.2 Verify a short spike is captured: the traffic generator's incident peak appears in at least one collected step, and compare a few collected values with the same values read directly from Datadog
- [x] 7.3 Measure and record: requests per minute against the limit, time from step end to message, number of lines and messages per minute
- [x] 7.4 Keep the recording as replay test data (in `app/collector/src/test/resources/recordings/`, small sample) and verify the replay test uses it

## 8. Documentation and checks

- [x] 8.1 Map every scenario in `specs/metric-collection/spec.md` (and the two open scenarios from `message-contracts`) to the test or run that proves it, and record the results in `docs/verification/add-collector.md`
- [x] 8.2 Update `HANDOFF.md` and `docs/platform.md` (how to run with recording or replay) and verify the documented commands work

## Workflow follow-up

- The user reviews and approves this change before any task starts.
- After the tasks are done, the user explains the Collector back in a few sentences; then archive.
