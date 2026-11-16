# Tasks

## 1. Message definitions

- [x] 1.1 Replace `placeholder.proto` with `metric_series.proto`, `metric_points.proto` and `incident.proto` (design C2), and verify the build generates their Java classes
- [x] 1.2 Add `GetHistory` and `GetDaySummary` to `store_service.proto` (design C5) and verify the build generates the new gRPC stubs
- [x] 1.3 Write a round-trip test for each message (build, turn into bytes, read back, compare) and verify it passes, replacing the old `ContractsCheckTest`

## 2. Shared helpers in `contracts`

- [x] 2.1 Add `SeriesIds` (design C3) and verify by tests: same id for tags in any order, different id for any different tag, a fixed known id for a fixed input, and no clash when a tag value contains separators
- [x] 2.2 Add `ContractChecks` (design C7) and verify by tests: a missing series id, points out of order, a non-finite value, an unset enum and a series id that does not match its descriptor are each reported with a clear reason
- [x] 2.3 Add `ContractJson` (design C8) and verify by a test that the JSON field names are the contract's own (`series_id`)
- [x] 2.4 Add `Topics` with topic names and the key for each message (design C8) and verify by a test that points and descriptors of one line share a key

## 3. Compatibility guard

- [x] 3.1 Add the baseline comparison test and the first baseline file (design C6), and verify it passes on the current contracts
- [x] 3.2 Verify the guard fails, naming the field, when a test copy of a contract renumbers or retypes a field, and passes when a field is added
- [x] 3.3 Verify an older reader ignores a field it does not know, with a test that adds an unknown field to `MetricPoints` bytes and reads them back

## 4. Services follow the contracts

- [x] 4.1 Make the Store's gRPC service offer `GetHistory` and `GetDaySummary` (empty answers until `add-store`) and verify with the gRPC test client that both answer
- [x] 4.2 Replace the Notifier's `incidents` route with `incidents.live` returning JSON (empty until `add-notifier`) and verify with the RSocket test client

## 5. Documentation and checks

- [x] 5.1 Write `docs/contracts.md`: each message in plain words, who sends and reads it, its topic and key, and one example in JSON; verify every example is produced by `ContractJson`
- [x] 5.2 Map every scenario in `specs/message-contracts/spec.md` to the test or check that proves it, run them all, and record the results in `docs/verification/add-contracts.md`
- [x] 5.3 Update `HANDOFF.md` and verify it names the new files and next change

## Workflow follow-up

- The user reviews and approves this change before any task starts.
- After the tasks are done, the user explains the contracts back in a few sentences; then archive.
