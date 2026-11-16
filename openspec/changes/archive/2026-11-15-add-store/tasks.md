# Tasks

## 1. Database

- [x] 1.1 Check that the TimescaleDB image offers continuous aggregates and retention policies, and verify with a throw-away table in a temporary schema
- [x] 1.2 Add Flyway and the migrations of design S1 (the continuous aggregate outside a transaction) and verify on the platform database that the tables, hypertable and per-minute view exist

## 2. Settings and connection

- [x] 2.1 Add the settings of design S5 with validators, and verify by tests that bad values are rejected
- [x] 2.2 Pass `PLATFORM_DB_PASSWORD` to the Store only (in `scripts/system.sh`) and verify the Store connects while no settings file or log contains the password

## 3. Writing

- [x] 3.1 Add the repositories with the upserts of design S2 and verify against the database: a point stored twice stays one row, a corrected value replaces the old, points before their description are stored
- [x] 3.2 Add the consumer loop (checks, set aside, merge, one transaction, commit after write) and verify by tests that a broken message between two good ones is set aside and counted
- [x] 3.3 Verify crash safety: stop the Store mid-stream and restart it, then check every point is stored once

## 4. Storage limits

- [x] 4.1 Apply the retention and refresh policies from settings at start and on settings change (defaults 2 days), and verify in TimescaleDB's job list that each policy exists once with the configured times, and that a 30-day limit is rejected
- [x] 4.2 Add the size alarm (design S3) and verify with a temporarily low `max-database-size` that health turns DEGRADED naming the size and largest table, and returns to UP when the limit is raised

## 5. Answers

- [x] 5.1 Answer `GetHistory` (raw and per minute, chunks of at most 1000, refused bad ranges) and verify by tests and the gRPC client
- [x] 5.2 Add `GetSummary` to the shared contract (design S4b), verify the compatibility guard passes against the old baseline, then refresh the baseline on purpose and add it to `docs/contracts.md`
- [x] 5.3 Answer `GetSummary` for any window and `GetDaySummary` as its shortcut, and verify count, lowest, highest, average and p95 against values computed independently, that a 6-hour window holds exactly its points, that a 40-day window is refused, and that a day equals its midnight-to-midnight window

## 6. Health

- [x] 6.1 Add the health details of design S6 and verify them on `/actuator/health`

## 7. Live check with the recording

- [x] 7.1 Run the Store with the Collector replaying `recordings/run-1` and verify: every finished point in Kafka is in the database once, and 95% are saved within 5 seconds of arriving
- [x] 7.2 Verify the history answer for the `/v1/invoices` latency line shows the incident peaks found in the Collector's live run
- [x] 7.3 Record the database size after the replay and compare it with the estimate in design S3

## 8. Documentation and checks

- [x] 8.1 Map every scenario in `specs/metric-storage/spec.md` to the test or run that proves it, and record the results in `docs/verification/add-store.md`
- [x] 8.2 Update `HANDOFF.md` and `docs/platform.md` (database tables, how to look at the data) and verify the documented commands work

## Workflow follow-up

- The user reviews and approves this change before any task starts.
- After the tasks are done, the user explains the Store back in a few sentences; then archive.
