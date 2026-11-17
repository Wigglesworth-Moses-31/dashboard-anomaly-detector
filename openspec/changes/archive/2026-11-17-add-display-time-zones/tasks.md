# Tasks

## 1. Settings

- [x] 1.1 `SharedSettings.displayTimeZones` with default and validators (design Z1); verify by tests: missing → the three defaults, `Asia/Kolkatta` refused, 5 zones refused, empty refused
- [x] 1.2 `Times.format` in service-kit (design Z3); verify by tests: winter `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`, summer `09:04:10 EDT`, `04:40 IST (18 Nov)`, minutes-only form

## 2. Page

- [x] 2.1 Notifier sends `display_zones` on the snapshot's end line and re-snapshots when it changes; verify by test
- [x] 2.2 `app.js`: formatters and labels per zone (design Z2) for every time on the page; verify with the page's code in the JavaScript engine: the three scenarios, a one-zone list, labels IST and EST/EDT; then in the browser on the running system

## 3. Reports and setup

- [x] 3.1 Reporter uses `Times.format`; verify by the Reporter's tests (times in three zones)
- [x] 3.2 `config-template/application.yml` (`time-zone: UTC`, `display-time-zones`), `setup.sh` (no zone question, default UTC), README upgrade note; verify setup in a clean copy

## 4. Checks

- [x] 4.1 Run all tests; `docs/verification/add-display-time-zones.md`

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `add-incident-resolution` (proposal written).
