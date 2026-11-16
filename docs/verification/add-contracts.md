# Verification: add-contracts

Run on 15 Nov 2026. Every scenario of `openspec/changes/add-contracts/specs/message-contracts/spec.md`, with the
test or check that proves it. Build: `mvn clean verify` from `app/`, green 3 times in a row, **40 tests, 0
failures** (contracts 28, service-kit 6, collector 6).

| Spec scenario | Proved by | Result |
|---|---|---|
| Building the services: messages come from the shared module | No `.proto` file outside `contracts`; collector, store, detector, notifier and reporter all depend on `contracts` | **Pass** |
| Tags in a different order → same id | `SeriesIdsTest.tagOrderDoesNotMatter` | **Pass** |
| Different status code → different id | `SeriesIdsTest.anyDifferentTagGivesADifferentId` | **Pass** |
| (extra) separators inside a value cannot clash; ids match an independent Python implementation | `SeriesIdsTest.separatorsInsideValuesCannotCauseAClash`, `fixedInputGivesAFixedId` | **Pass** |
| A gap in the data → no point, not a 0 | The contract defines it (field comments, docs rule 2). Sending is the Collector's job | **Contract in place; behaviour to verify in `add-collector`** |
| Newest step → marked partial | `Point.partial` exists and is documented; examples use it. Marking is the Collector's job | **Contract in place; behaviour to verify in `add-collector`** |
| Newer sender, older reader | `UnknownFieldTest`: field 99 added to `MetricPoints` bytes; reader keeps every known field | **Pass** |
| Unsafe contract edit → build fails, names the field | `ContractCompatibilityTest` (renumber, retype, remove with/without reserve, add) and a real edit of `incident.proto` (`unit` 7 → 8): build failed with "lab.anomaly.v1.AffectedSeries field 7 (unit): removed without reserving its number" | **Pass** |
| Points without a series id → rejected with reason | `ContractChecksTest.missingSeriesIdIsReported` (+ out of order, NaN, unset enum, mismatched id, event without incident) | **Pass** |
| Viewer joins late → can show the incident from one event | Every `IncidentEvent` carries `Incident`; `ContractChecks` rejects an event without it; `RoundTripTest.incidentEvent` | **Pass** |
| Same event delivered twice → same event id | `event_id` is part of the event and required by `ContractChecks`; dropping repeats is the Notifier's job | **Pass** (contract); skipping duplicates verified in `add-notifier` |
| Two polls of one line → same key, received in order | `ContractJsonAndTopicsTest.descriptorAndPointsOfOneLineShareAKey`; live Kafka check on a temporary topic: 3 messages with one key on partition 1 at offsets 0, 1, 2 in send order, another key on partition 5 | **Pass** |
| Incident shown in the browser → JSON field names match | `ContractJsonAndTopicsTest.jsonKeepsTheContractsFieldNames`; `incidents.live` route serves the stream | **Pass** |

Services following the contracts (running system, small Java clients using each service's own libraries):
- Store gRPC: `GetHistory` and `GetDaySummary` answer (0 chunks, no data stored yet).
- Notifier RSocket: `incidents.live` opens (0 events); the old `incidents` route answers "No handler".
- Regression: `scripts/verify_runtime_configuration.py` still 7/7 Pass.

Problems found while verifying, and fixed:
1. **JSON key order was not fixed.** `group_tags` printed in whatever order the map was filled, which differed
   between runs and failed the docs check on a clean build. `ContractJson` now sorts map keys; a test proves two
   insertion orders print identically; 3 clean builds in a row are green.
2. A first "unsafe edit" test changed a field's type, which broke compilation before the guard ran, so it proved
   nothing. Replaced by a renumbering edit that compiles; the guard then stopped the build as intended.
3. A first live Kafka check mixed in late messages from a timed-out earlier attempt (out-of-order result).
   Redone on a fresh topic with offsets printed: exactly 4 messages, correct order.
4. Docs drift: changing one value in `docs/contracts.md` made `DocsExamplesTest` fail, naming the example.
