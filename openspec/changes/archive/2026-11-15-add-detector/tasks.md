# Tasks

## 1. Contract and settings

- [x] 1.1 Add the incident fields of design D8, verify the compatibility guard passes against the old baseline, then refresh the baseline on purpose and add the fields to `docs/contracts.md`
- [x] 1.2 Replace the Detector settings with those of design D4 (rules, general rule, grouping, silence, retention) with validators, rewrite `config-repo/detector.yml` with default rules fitted to the 226 recorded lines, and verify by tests that bad values (no signal, history over 2 days, factor 1, retention over 7 days) are rejected

## 2. What each line remembers

- [x] 2.1 Add the ring buffer, the sliding median (two heaps, lazy deletion), MAD by quickselect and the presence bit set (design D3), and verify by tests against a brute-force sort of the same window, including values leaving the window and repeated values

## 3. Rules and judging

- [x] 3.1 Add rule matching (first match, any case, cached per line) and verify by tests that every one of the 226 recorded lines gets the intended rule, that "Success rate % per service" is a success rate, that a 500 count is errors and a 200 count is traffic, and that an unknown metric gets the general rule
- [x] 3.2 Add the three measures (factor, points, wobble) with the smallest change, and the per-line states of design D5, and verify every scenario of the spec's judging requirements by tests (latency jump, one odd step, stricter rule, success rate in points, small wobble, error rate, traffic both ways, zero to one error, partial step, flapping, problem over, lasting change becomes normal)
- [x] 3.3 Add silence (dashboard watermark, presence at least 0.9) and "dashboard not arriving", and verify by tests: an upstream that stops while the dashboard continues, a rare error code, a whole dashboard stopping

## 4. Incidents

- [x] 4.1 Add the incident book (group key from settings, join or open, titles, update rules, closing after the calm period) and verify by tests: one API with three signals, two APIs, an upstream, a new dashboard with an `endpoint` tag before and after adding it to the grouping list
- [x] 4.2 Add the Flyway migrations and the transactional outbox (design D7) and verify against the database: a change writes the incident and its event in one transaction, two open incidents for one group are refused, and an event left unsent is sent after a restart
- [x] 4.3 Add the consumer loop (event time, repeats skipped, warm start from the Store, offsets committed after the database) and the outbox publisher, and pass the database password to the Detector in `scripts/system.sh`
- [x] 4.4 Add the hourly clean-up of closed incidents and sent events (design D9) and verify against the database that an incident closed 3 days ago is removed and one closed yesterday stays

## 5. Health

- [x] 5.1 Add the health details of design D10 and verify them on `/actuator/health`, including lines per rule, lines on the general rule by panel, open incidents and time to announce

## 6. Live check with the recording

- [x] 6.1 Judge the live run's values (as the Store kept them) in time order and replay `recordings/run-1` through Kafka with the Store and the Detector running, and verify: one `/v1/invoices` incident per problem once lines have history (08:08 to 08:40); every line of both dashboards watched by a rule; the Detector announces within 5 seconds of reading the deciding value
- [x] 6.2 List every other incident, decide for each whether it is a real effect of the recorded problem or a false alarm, tune the default rules in `config-repo/detector.yml` until false alarms are none or very few, and record the result
- [x] 6.3 Replay a second time after a Detector restart and verify every line makes the same decisions (spikes, calm times); record how grouping depends on the order lines are read
- [x] 6.6 Run the Collector live with the lab's traffic generator and verify each judged problem cycle gives one `/v1/invoices` incident with its latency and errors, and how long announcing takes after Datadog's data reaches Kafka
- [x] 6.4 Verify crash safety: stop the Detector with `kill -9` while an incident is open, restart it, and check the incident continues and closes without a second one, and that a reader of `incident-events` sees every change once after dropping repeats by event id
- [x] 6.5 Verify a settings change without restart: make the latency rule need 3 steps, check the health page shows the new settings version, then restore it

## 7. Documentation and checks

- [x] 7.1 Map every scenario in `specs/anomaly-detection/spec.md` to the test or run that proves it, and record the results in `docs/verification/add-detector.md`
- [x] 7.2 Update `HANDOFF.md` and `docs/platform.md` (incident tables, how to read incidents and the rules) and verify the documented commands work

## Workflow follow-up

- The user approved the proposal, spec and design on 15 Nov 2026 and asked to complete the change.
- After the tasks are done, the user explains the Detector back in a few sentences; then archive.
