# Proposal: add-settings-page

## Why

The user asked (17 Nov 2026) to manage **dashboards from the page** instead of copying JSON files into `config-repo`,
editing `collector.yml` and committing by hand: **create, read, update and delete** a dashboard's JSON (CRUD).

Decided with the user (17 Nov): the **regions are fixed**: the two set up with `setup.sh` (US and EU). The page shows
them but does not add or remove them, so no keys are ever typed into the page; keys stay in `.env` as today.

`add-reports-on-demand` is set aside (user, 17 Nov); this change and `add-system-page` come first.

## What Changes

| Operation | In plain words |
|---|---|
| **Read (list)** | A **Settings** tab on the live page (`localhost:8104`): the two regions (name, site, keys present ✓/✗, never the keys) and, per region, its dashboards: title, file, id, which saved views are watched (all / some / none), number of panels and views |
| **Read (one)** | Open a dashboard: its panels by group, its variables with their defaults, its saved views, and the filters each watched view uses; download its JSON |
| **Create** | Pick a region, upload an exported JSON. The system reads it with the Collector's own code and shows its title, panels, groups and saved views; choose **all saved views, some, or none**, optionally the id for links. **Check** asks every panel once (read-only, like `check-dashboards.sh`) and shows lines per panel. **Add** saves the file and its entry as one commit |
| **Update** | Replace a dashboard's JSON with a newer export (same checks, a diff of panels and saved views added or removed is shown first), or change which saved views are watched, or its id; one commit each |
| **Delete** | After a confirmation, the file and its entry are removed in one commit; its lines stop being collected within one poll |
| **Every change is a commit** | One small commit per action in `config-repo` ("settings page: update eu/shop.json"), visible and undoable with Git. Hand edits keep working; the page refuses to write a file that has uncommitted hand edits |
| **Checked before saving** | Not JSON, no widgets, an unknown saved view, an id that differs from the file's: refused with the reason, nothing written. A dashboard already in the region (same file name) cannot be created twice |
| **Applied without restart** | The Collector picks up every change through the existing settings reload (add-dashboard-json) |
| **Only from this machine** | The page and the settings API listen on `127.0.0.1` only. Today every service listens on all network interfaces; the Config Service and the Notifier (which serves the page) are bound to this machine |

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
| Only local | From another machine, the page and the settings API cannot be reached |

## Capabilities

### New Capabilities
- `settings-page`: listing regions and dashboards; creating, reading, updating and deleting dashboard JSON from the
  page; checked before saving; each change one commit.

### Modified Capabilities
- `runtime-configuration`: settings may be changed by the system as commits (only dashboard entries and files); the
  page and the settings API are local only.

## Out of scope

| Not in this change | Why |
|---|---|
| Adding or removing regions; typing keys | Regions are fixed (user, 17 Nov); `setup.sh` sets them |
| Editing other settings (Detector rules, report window...) from the page | Dashboards were asked for |
| Editing a dashboard's JSON text in the page | Re-export from Datadog and upload; `filters:` can still be edited by hand |
| Users, sign-in | One person, one machine; local-only binding instead |
| Reports on demand | Set aside |

## Impact

- `app/config-service`: the dashboard write API (files under `dashboards/<region>/` and the region's `dashboards:`
  entries in `collector.yml`, one commit per change), local-only binding.
- `app/collector`: a check endpoint (parse an uploaded JSON, list panels, groups and saved views, compare with the
  current version, optionally ask each panel once) reusing `DashboardFiles`, `WatchedView`, `PollPlan`, `Batch`.
- `app/notifier`: the Settings tab (page) and a local proxy to the settings API and the check, so the page talks to
  one address.
- README.
