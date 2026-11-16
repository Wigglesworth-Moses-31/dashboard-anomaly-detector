# Tasks

## 1. Contract and Store

- [x] 1.1 Add `StoreService.RemoveRawBefore` with its messages (design R6), verify the compatibility guard passes against the old baseline, then refresh the baseline on purpose and add it to `docs/contracts.md`
- [x] 1.2 Answer `RemoveRawBefore` in the Store (drop whole chunks, delete the rest in one-hour slices, floor at now minus 3 hours, refuse a time in the future), remove the unused `raw-retention` setting, and verify against the database: points before the time are gone, points from the floor on remain, per-minute summaries of the removed hours remain, a time in the future is refused

## 2. Reporter

- [x] 2.1 Replace the Reporter settings with those of design R8 with validators, rewrite `config-repo/reporter.yml`, and verify by tests that a 48-hour window, a 5-hour window and a catch-up over 2 days are rejected
- [x] 2.2 Add the windows of design R1 (start at midnight in the shared zone, keys, grace, catch-up) and verify by tests: the day key, four 6-hour windows named `_00-06` to `_18-24`, a window not due before its grace, the missed windows of a night oldest first
- [x] 2.3 Add the incident and line readers (Kafka, design R3) and the Store client (design R4)
- [x] 2.4 Add the report writer of design R5 and verify by tests: headline numbers (incidents, previous window, longest, still open, completeness), the incidents table oldest first, one section per incident with peaks, normals, times normal and links, groups that broke most, the top 10 lines that moved most (min-heap), unclassified lines, a quiet window, escaped titles, the completion line
- [x] 2.5 Add the per-window state machine of design R2 (atomic writes, SHA-256 completion line, verified before clean-up) and verify by tests: a restart at each step finishes without a second report; a failed write removes nothing; a tampered or truncated file is not verified
- [x] 2.6 Add the scheduler, the on-request endpoint, report rotation (design R7) and health (design R9)

## 3. Live checks

- [x] 3.1 Run the Reporter with the platform and the Store: ask for the report of 15 November and verify its incidents and peaks match what the Detector announced, and the data completeness
- [x] 3.2 Verify exactly once and the clean-up live: stop the Reporter between writing and cleaning, start it again, and check one file, the clean-up done, the raw points reduced as designed, the per-minute summaries kept
- [x] 3.3 Verify a failed write removes nothing (output folder not writable), then recovers
- [x] 3.4 Verify a settings change without restart: a 6-hour window gives 6-hour report names; a 48-hour window is rejected

## 4. Documentation and checks

- [x] 4.1 Map every scenario in `specs/incident-reporting/spec.md` and `specs/metric-storage/spec.md` to the test or run that proves it, and record the results in `docs/verification/add-reporter.md`
- [x] 4.2 Update `HANDOFF.md` and `docs/platform.md` (where reports are, how to ask for one, settings) and verify the documented commands work

## Workflow follow-up

- The user approved the proposal, spec and design on 16 Nov 2026 and asked to build.
- After the tasks are done, the user explains the Reporter back in a few sentences; then archive.
