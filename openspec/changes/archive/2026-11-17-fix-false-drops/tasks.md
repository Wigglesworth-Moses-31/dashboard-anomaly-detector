# Tasks

## 1. Collector: settle delay and late changes

- [x] 1.1 Add `settle-delay` to the Collector settings (default 60 s when missing; validator: 0 or more, shorter than `recent-window`) and to `config-template/collector.yml`; verify by tests: missing → 60 s, 3 min with a 2 min window refused
- [x] 1.2 Mark a point partial while `start + step + settle-delay > receivedMs` (design F1); verify by tests: the 13:04:10 example (count 1 at 13:04:25 and 9 at 13:04:55 partial, 13 at 13:05:25 finished), settle 0 = old behaviour, existing replay fixture unchanged with settle 0
- [x] 1.3 Count late changes per source in the Emitter (design F2) and show them on health with a warning when later than `settle-delay`; verify by tests: one change 50 s late with settle 30 s → count 1, latest 50 s, the warning

## 2. Detector rules

- [x] 2.1 Add `status=4*`, `status=5*` to the `errors` rule in `config-template/detector.yml`; point Detector tests at `config-template/detector.yml`; verify by tests: `status=404` 13 → 0 opens nothing, `status=500` 2 → 40 opens "errors up"; all Detector tests pass on the template

## 3. Page

- [x] 3.1 Drops as percentages (design F4); verify with the exact meter code and the screenshot's values (not in a browser, see verification): 0 vs 5 → "dropped to 0 (−100%)", 1 vs 13 → "−92%", rises unchanged

## 4. Docs and checks

- [x] 4.1 README: `settle-delay` in the settings table, the late-change numbers in troubleshooting, the upgrade note for `detector.yml`; `docs/verification/fix-false-drops.md`
- [x] 4.2 Run all tests and record the results; live late data is not observable on the trial (no traffic): recorded as not verified, first numbers on the user's machine

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `add-incident-history`.
