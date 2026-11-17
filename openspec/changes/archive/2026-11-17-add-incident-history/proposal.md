# Proposal: add-incident-history

## Why

The user ran the system on a real account (17 Nov 2026) and asked for two things on the live page:

1. **A clear hierarchy instead of a link.** Each anomaly should read top-down: **dashboard (and saved view) → panel
   group → metric panel → line → value**, for example *Shop · Payments → API Gateway Metrics → Request
   Counts by Status Code → status=200 → 37 (normal 670, −94%)*. Today a line shows only its panel title, the group is
   missing, long values are cut ("api=/accounts/_accountid_/devices/_deviceid_/pr…", "Request Counts by
   Stat…"), and the dashboard appears as a link the user does not need.
2. **The line's past.** For an anomaly on one metric line: **when it last happened, every time it happened in the last
   2 days, and the reason given each time.**

The pieces exist: every line has a stable id (the same line across incidents), the Collector knows each panel's group
(`PollUnit.View.path`, add-dashboard-json), and the Notifier already reads every incident event of the last 2 days
from Kafka at start.

## What Changes

| Step | In plain words |
|---|---|
| **The group travels with the line** | The line description's panel view gains the panel's **group / section** ("API Gateway Metrics"); the Detector copies it into each affected line of an incident. New contract fields only: older readers keep working |
| **Hierarchy on the page** | Each affected line is shown as a path: **view → group → panel → line tags → value**, with value, normal and the change (−94%, 8.8×). Lines are grouped under their view and panel, so an incident touching three panels shows three small trees, not a flat list |
| **Nothing cut off** | Full titles and tag values, wrapped onto more lines when long; no "…" |
| **No dashboard links on the page** | Removed from incident rows (the Datadog link stays available in the report and the incident data, for whoever needs it) |
| **History per line** | Opening a line shows: *last time before this one* (when, how long, peak against normal, reason), and *every time in the last 2 days* (newest first). "Reason" shows "—" until reasons exist (`add-incident-resolution`, next) and fills in automatically once they do |
| **Kept small and fast** | The Notifier keeps, from the incident events it already reads, a 2-day index **line id → its incidents** (a few numbers each). Opening a line reads its list: no database call, no new service |

## How we will know it works

| Check | Expected |
|---|---|
| Hierarchy | An incident on `status=200` of "Request Counts by Status Code" in group "API Gateway Metrics" of view "Shop · Payments" shows exactly that path and "37 (normal 670, −94%)" |
| Long values | A 120-character `api` value is shown whole |
| History | A line that had incidents at 22:14 yesterday and 09:03 today shows, when the 13:04 one is opened: last time 09:03 (and how long, peak, normal), and 3 times in 2 days |
| After a restart | The Notifier rebuilt from Kafka shows the same history |
| Older than 2 days | Not shown (Kafka keeps 2 days); the page says "in the last 2 days" |
| Old messages | Incidents from before this change (no group field) show "view → panel", without a group |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `message-contracts`: the panel view of a line and an incident's affected line carry the panel's group.
- `metric-collection`: the Collector fills the group from the dashboard (sections included).
- `anomaly-detection`: incidents carry each line's group.
- `live-alerting`: the hierarchy, full text, no links on the page, and the line's history of the last 2 days.

## Out of scope

| Not in this change | Where |
|---|---|
| Entering and keeping a reason; "calm, needs a reason" | `add-incident-resolution` (next) |
| Reports grouped by panel | `add-reports-on-demand` |
| History older than 2 days | Would need the Detector's database as a source; Kafka and the database both keep 2 days today |

## Impact

- `app/contracts`: `View.panel_path`, `AffectedSeries.panel_path` (new fields; the compatibility check passes).
- `app/collector`: fill `panel_path` from the planned view.
- `app/detector`: copy it into incidents.
- `app/notifier`: the 2-day line index, a history endpoint for the page, the page (hierarchy, full text, history,
  no links).
- No new topic, no database change, nothing in Datadog.
