# Verification: add-settings-page

18 Nov 2026. Regions (at most two, US and EU) and their dashboards are managed from the Settings tab of the live
page; keys go only to `.env`; every change is one commit in `config-repo`; a dashboard's JSON can be edited in the
page.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **288 run, 0 failed**, 23 skipped (live-only) | |
| Collector | 120 passed | `regions.yml` wins over `collector.yml`, and `collector.yml` alone still loads; keys read from `.env` (before the environment); new keys restart only that lane; `.env` keeps its other lines and comments, mode 600; no key in any log line; only Datadog key names can be read; key check against a fake Datadog: valid, wrong API key (not echoed), wrong application key, only us/eu, both keys; dashboard check: summary, +1 panel diff, not JSON / no widgets / empty refused, each panel asked once per view, unknown saved view refused |
| Config Service | 10 passed | Add EU (one commit, no key, refresh), a third region and an unknown one refused; add / duplicate / replace (views kept) / watch views / delete a dashboard; an edit keeps the entry's views and filters and commits "edit"; removing a region takes its files; bad JSON writes nothing; hand edits and a stale page refused; the one-time move from `collector.yml` keeps comments and settings; the old single-site form is read-only; safe file names |

## Live (this machine)

| Check | Result |
|---|---|
| Local only | Config Service :8888, Collector :8101, Notifier :8104 and the live stream :7104 listen on `127.0.0.1` only; the machine's network address is refused |
| Old single-site form | Before the conversion the tab showed it read-only with the reason |
| Conversion | `config-repo` converted by hand to `regions.yml` (backup branch `before-regions`); the Collector's check: 2 views, 36 questions, as before |
| Keys typed in the page (by the user) | "keys ✓"; the Collector log: "keys changed, starting it again" → "ready", no restart; the key values found **0 times** in every service log and in the whole `config-repo` history |
| JSON editor | Opens at the top with line numbers; a deleted comma shows "line 3, column 5: Expected ',' or '}' after property value", Save off; a renamed panel: Check shows "+1 panel: API Metrics › Latencies (p95)", "−1 panel: API Metrics › Latencies"; Save → commit "settings page: edit us/w2f-pqs-ghd.json" changing only that line, `filters: env=prod` kept; the Collector loaded the commit within 2 s (`name=collector,regions`); renamed back the same way: the dashboard files equal the backup |
| Dashboard flow | Check with "ask each panel once" reached Datadog (0 of 24 panels with lines: no traffic was being sent); Add → commit, Collector settings version +1; the same file again → 409 "use Replace"; no widgets → 400; stale page → 409; an uncommitted hand edit of `regions.yml` → 409 "commit or discard your edit first"; Delete → commit, file gone |

## Not checked live

| What | Why |
|---|---|
| Adding and removing EU | Needs EU keys typed by the user; covered by the Config Service and key tests |

## Notes

| Seen | Why it is right |
|---|---|
| `regions.yml` lost two hand comments after the first page write | The file says so in its header: the page keeps settings, not comments; `collector.yml` keeps its comments |
| The panels return no lines | Nothing sends data to the trial at the moment; the settings and keys are fine (no errors, keys valid) |
