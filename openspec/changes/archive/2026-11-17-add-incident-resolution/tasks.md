# Tasks

## 1. Contract and platform

- [x] 1.1 `INCIDENT_STATE_CALM`, `Incident.reason`, `Incident.resolved_ms`, `ResolveIncident`, topic `incident-commands` in `Topics` and `create-topics.sh`, checks for the command (design S1); verify the compatibility check against the old baseline, refresh it, update `docs/contracts.md`

## 2. Detector

- [x] 2.1 Calm instead of closed, reopen from calm (no time limit), restore OPEN and CALM (design S2); verify by tests: calm after 2 min with end time, not closed; a spike an hour later reopens the same incident (OPENED, same id); restored after a restart
- [x] 2.2 Read `incident-commands` in the engine and resolve (design S3); verify by tests: calm + reason → CLOSED with reason and time; open + reason → CLOSED; empty, 501 characters, unknown and already closed → nothing; the same command twice → one CLOSED; the next spike after closing opens a new incident; reason saved in the database (live test)
- [x] 2.3 Re-announce waiting incidents every 12 h (design S4); verify by test with a clock

## 3. Notifier

- [x] 3.1 `POST /incidents/{id}/reason` → command (Kafka producer), validation (design S3); verify by test: 202 with a command id, empty and too long refused (400)
- [x] 3.2 History reads the reason; verify by test

## 4. Page

- [x] 4.1 "Needs a reason (n)", reason box, saving / not saved, reasons on closed rows (design S5); verify with the page's code in the JavaScript engine and in the browser

## 5. Checks and docs

- [x] 5.1 Live: run the system; give a reason to a stale open incident and to a calm one in the browser; check the database row, the event, the page and the line history; restart the Notifier and the Detector and check calm incidents and reasons are still there
- [x] 5.2 README (reasons, the "Needs a reason" list), `docs/verification/add-incident-resolution.md`; run all tests

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `add-reports-on-demand`.
