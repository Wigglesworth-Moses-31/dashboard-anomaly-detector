# Tasks

## 1. Contract

- [x] 1.1 Add `View.panel_path = 4` and `AffectedSeries.panel_path = 12` (design H1); verify the compatibility check passes against the old baseline, refresh the baseline on purpose, update `docs/contracts.md`

## 2. Collector and Detector

- [x] 2.1 Collector: `AnswerTranslator` fills `View.panel_path` from the planned view (design H2); verify by test: a panel below the section "Upstream Service Metrics" is described with that group, line ids unchanged (replay fixture)
- [x] 2.2 Detector: copy the primary view's `panel_path` into each affected line (design H2); verify by test: an incident on a grouped panel carries group and panel

## 3. Notifier: history

- [x] 3.1 The 2-day line index and `GET /history?line=&before=` (design H4); verify by tests: three incidents of a line give "last" and a list of 3 newest first; an update of an incident replaces its entry; entries ended more than 2 days ago are dropped; the index rebuilt from the same events after a restart is the same; unknown line → empty

## 4. Page

- [x] 4.1 The tree per incident (view → group → panel → tags → value), full text, no links on rows (design H3); verify in the browser with the page's demo data and a 120-character tag value
- [x] 4.2 "▸ history" per line: last time and the 2-day list, "—" for reasons, "no earlier incident in the last 2 days"; verify in the browser against the running Notifier

## 5. Docs and checks

- [x] 5.1 README (what the page shows), `docs/verification/add-incident-history.md`
- [x] 5.2 Live: replay a recording through the whole system and check the tree and history of a line with several incidents in the browser; run all tests

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `add-incident-resolution`.
