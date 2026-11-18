# Tasks

## 1. Settings files and keys

- [x] 1.1 `regions.yml` read with `collector.yml` (`spring.cloud.config.name: collector,regions`); `setup.sh` writes it; verify by test: `regions.yml` wins, `collector.yml` without `sources` still loads (design S2)
- [x] 1.2 Collector reads keys from `.env` (`LAB_ENV_FILE`) on every reload, `.env` before environment; key fingerprint in `LaneConfig`; `system.sh` passes `LAB_ENV_FILE`, no `DD_*`; verify by tests: new keys rebuild only that lane, no key in any log line (design S4)

## 2. Collector endpoints (local only)

- [x] 2.1 `POST /keys/check` and keys written/removed in `.env` (whole-file rewrite, mode 600, atomic); `Secret` type; Datadog errors mapped to plain reasons; verify by tests with a fake Datadog: valid, wrong API key, wrong application key, other lines of `.env` kept (design S4)
- [x] 2.2 `POST /dashboards/check`: parse, panels by group, variables, views, diff against the current file; optional "ask each panel once" through the lane; 5 MB limit; verify by tests: the skeleton export, +1 panel diff, not JSON, no widgets (design S5)

## 3. Config Service writes

- [x] 3.1 Write API: add/remove region, create/update/delete dashboard; one lock, `If-Match` commit id, hand-edit refusal, atomic file write, JGit commit, bus refresh; at most 2 regions, `us`/`eu` only (design S3)
- [x] 3.2 One-time move of `sources` from `collector.yml` to `regions.yml`, equal-after-parse check; verify by tests on a copy of the template and on a hand-edited file (design S2)

## 4. Notifier and page

- [x] 4.1 `/settings/**` proxy to localhost; `server.address: 127.0.0.1` on the Config Service, Collector and Notifier (HTTP and RSocket) (design S6)
- [x] 4.3 JSON editor: Edit opens the committed JSON as it is; line numbers, mistakes with line and column while typing, Format, Undo all; Check (summary and diff), Save as one commit "edit"; the entry unchanged (design S5b); verify in the browser
- [x] 4.2 Settings tab: regions (add, replace keys, remove), dashboards (list, open, download, upload, check, replace, delete), feedback (design S7); verify in the browser on the running system

## 5. Checks and docs

- [x] 5.1 (EU add/remove skipped by the user, 18 Nov; covered by tests) Live: add EU with the trial's keys, upload the skeleton export, check panels, add, see it collected without restart; replace with +1 panel; delete; remove EU; then search logs, `config-repo` history and page answers for the keys (none found); from another machine nothing answers
- [x] 5.2 README (Settings tab, `regions.yml`, upgrade note), `docs/verification/add-settings-page.md`; run all tests

## Workflow follow-up

- The user explains the change back, then it is archived.
- Then `keep-one-week`, then the paused "every silent line is BAU" in `add-line-reasons`.
