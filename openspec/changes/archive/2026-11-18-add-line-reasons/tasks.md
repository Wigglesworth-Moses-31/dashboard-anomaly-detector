# Tasks

## 1. Contract and settings

- [x] 1.1 `AffectedSeries.explanation = 13`, `explained_ms = 14`, `bau = 15`; `ResolveIncident.series_id = 5` (design L1); compatibility check against the old baseline, refresh it, `docs/contracts.md`
- [x] 1.2 `DetectorSettings.bau` with defaults and checks, `config-template/detector.yml` (design L2); verify by tests: missing → the two defaults, an unknown direction refused

## 2. Detector

- [x] 2.1 Per-line explanations, BAU on join, cleared on a new spike, resolve for one line or all, the closing check and summary (design L3); verify by tests: the mixed incident (only the 404 line waits; closes after its reason when calm), one line of two, same reason for all, only BAU closes by itself with "BAU", errors down BAU, the line spikes again, empty or unknown does nothing, the BAU list changed without restart

## 3. Notifier and page

- [x] 3.1 Endpoint with `line`, history shows a line's own explanation or "BAU", notifications skip BAU-only incidents (design L4); verify by tests
- [x] 3.2 Page: reason per line, "same reason for all", BAU dimmed, feedback per line; verify in the browser on the running system (closing a mixed incident line by line)

## 4. Checks and docs

- [x] 4.1 README (BAU, reasons per line, upgrade note for `detector.yml`), `docs/verification/add-line-reasons.md`; run all tests

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `improve-incident-page`, then `add-settings-page`.
