# Tasks

## 1. Detector

- [ ] 1.1 `*` matches any signal in `BauSettings`; the default list gains `{ signal: "*", direction: silent }`; `config-template/detector.yml` (design B1); verify by tests
- [ ] 1.2 Silent-only BAU incidents close at once; waiting incidents marked again when the BAU list loads and on restore (design B2, B3); verify by tests: the silence scenario, the "from before" scenario, the setting changed

## 2. Checks and docs

- [ ] 2.1 Live: the page's long-open silent incidents close with "BAU" after the start; README upgrade note; `docs/verification/silent-is-bau.md`; run all tests

## Workflow follow-up

- The user explains the change back, then it is archived.
