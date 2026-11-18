# Proposal: add-settings-page

## Why

The user asked (17 Nov 2026) to manage **dashboards from the page** instead of copying JSON files into `config-repo`,
editing `collector.yml` and committing by hand: **create, read, update and delete** a dashboard's JSON (CRUD).

Changed by the user (18 Nov): **regions are added and removed from the page too, at most two** (US and EU), with
their keys typed into the page. (On 17 Nov they were fixed by `setup.sh`.) The keys go only to `.env` on this machine:
never shown again, never committed, never logged.

`add-reports-on-demand` is set aside (user, 17 Nov); this change and `add-system-page` come first.

## What Changes

| Operation | In plain words |
|---|---|
| **Read (list)** | A **Settings** tab on the live page (`localhost:8104`): the regions (name, site, keys present and valid ✓/✗, never the keys) and, per region, its dashboards: title, file, id, which saved views are watched (all / some / none), number of panels and views |
| **Add a region** | While fewer than two exist: pick **US** (`datadoghq.com`) or **EU** (`datadoghq.eu`), type its API key and application key. The keys are first checked with Datadog (read-only: one key-validation call, one small metrics query); wrong keys are refused with the reason. Then the keys are written to `.env` (only this machine can read it) and the region's entry to `collector.yml` (one commit, names of the key variables only). The Collector starts the region's lane without a restart |
| **Replace a region's keys** | Type new keys (same checks); only `.env` changes; the lane picks them up without a restart |
| **Remove a region** | After a confirmation naming its dashboards: its entry and its dashboard files go in one commit, its keys are removed from `.env`; its lane stops within one poll. Its incidents and data stay until they age out |
| **Read (one)** | Open a dashboard: its panels by group, its variables with their defaults, its saved views, and the filters each watched view uses; download its JSON |
| **Create** | Pick a region, upload an exported JSON. The system reads it with the Collector's own code and shows its title, panels, groups and saved views; choose **all saved views, some, or none**, optionally the id for links. **Check** asks every panel once (read-only, like `check-dashboards.sh`) and shows lines per panel. **Add** saves the file and its entry as one commit |
| **Update** | Replace a dashboard's JSON with a newer export (same checks, a diff of panels and saved views added or removed is shown first), or change which saved views are watched, or its id; one commit each |
| **Delete** | After a confirmation, the file and its entry are removed in one commit; its lines stop being collected within one poll |
| **Edit the JSON** (user, 18 Nov) | **Edit** opens the dashboard's JSON as it is in a text editor in the page: line numbers, the exact place of a JSON mistake while typing, **Format**, **Undo all**. **Check** reads the edited text with the Collector's code and shows the panels and views added or removed; **Save** commits it (same checks as Update). Hand edits and a stale page are refused the same way |
| **Every change is a commit** | One small commit per action in `config-repo` ("settings page: update eu/shop.json"), visible and undoable with Git. Hand edits keep working; the page refuses to write a file that has uncommitted hand edits |
| **Checked before saving** | Not JSON, no widgets, an unknown saved view, an id that differs from the file's: refused with the reason, nothing written. A dashboard already in the region (same file name) cannot be created twice |
| **Applied without restart** | The Collector picks up every change through the existing settings reload (add-dashboard-json) |
| **Only from this machine** | The page and the settings API listen on `127.0.0.1` only. Today every service listens on all network interfaces; the Config Service and the Notifier (which serves the page) are bound to this machine. Keys travel only between them on this machine |
| **Keys stay secret** | A key is sent once, from the key field to `.env`. No answer, log line, commit, error message or page ever contains a key; the page shows only "keys present ✓" and the last 4 characters are not shown either |

## How we will know it works

| Check | Expected |
|---|---|
| List | Two regions, each with its dashboards, views and keys present; no key anywhere in the answer |
| Create | Uploading an export with 23 saved views shows panels and views; "all" + Add → one commit with the file and the entry; the Collector collects the new views within one poll, no restart |
| Check | A view whose filters match nothing shows "0 lines" for its panels before anything is saved |
| Update | A newer export with one panel more shows "+1 panel" first; after Update the panel is collected; changing "all" to two views leaves two views |
| Delete | After confirming, the file and entry are gone in one commit; its lines stop within one poll |
| Refused | Broken JSON, no widgets, an unknown view, a duplicate: nothing written, the reason shown |
| Hand edits | With an uncommitted hand edit in `collector.yml`, a change is refused with "commit or discard your edit first" |
| JSON editor | Open `shop.json`, rename a panel, Check shows "+1 panel, −1 panel", Save → one commit "settings page: edit us/shop.json"; the renamed panel is collected from the next poll |
| JSON mistake | A missing comma shows "line 41, column 7: expected ','" while typing; Save stays off until the text is valid |
| Add a region | With only US: add EU with valid keys → `.env` has the EU keys, `collector.yml` one commit with the EU entry (no key in it); the EU lane runs within one poll, no restart |
| Wrong keys | A wrong application key → "Datadog refused the application key", nothing written |
| At most two | With US and EU present, "Add region" is not offered and the API refuses a third |
| Remove a region | EU removed → its entry and files gone in one commit, its keys gone from `.env`, its lane stopped |
| No key leaks | After adding a region: `grep` for the key in the logs, `config-repo` history and page answers finds nothing |
| Only local | From another machine, the page and the settings API cannot be reached |

## Capabilities

### New Capabilities
- `settings-page`: listing regions and dashboards; adding and removing regions (at most two) with their keys;
  creating, reading, updating and deleting dashboard JSON from the page; checked before saving; each change one commit.

### Modified Capabilities
- `runtime-configuration`: settings may be changed by the system as commits (only region and dashboard entries and
  files); keys written to `.env` and read from it on reload; the page and the settings API are local only.

## Out of scope

| Not in this change | Why |
|---|---|
| A third region, or sites other than US and EU | At most two, US and EU (user); others can still be added by hand in `collector.yml` |
| Editing other settings (Detector rules, report window...) from the page | Dashboards were asked for |
| Users, sign-in | One person, one machine; local-only binding instead |
| Reports on demand | Set aside |

## Impact

- `app/config-service`: the region and dashboard write API (region entries and files under `dashboards/<region>/`
  and the region's `dashboards:` entries in `collector.yml`, one commit per change; keys to `.env`), local-only
  binding.
- `app/collector`: reads a region's keys from `.env` when its settings reload (today: environment variables at start
  only), so a new region or new keys need no restart.
- `app/collector`: a check endpoint (parse an uploaded JSON, list panels, groups and saved views, compare with the
  current version, optionally ask each panel once) reusing `DashboardFiles`, `WatchedView`, `PollPlan`, `Batch`.
- `app/notifier`: the Settings tab (page) and a local proxy to the settings API and the check, so the page talks to
  one address.
- README.
