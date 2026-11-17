# Proposal: add-display-time-zones

## Why

The user asked (17 Nov 2026): **wherever a time is shown, show three time zones: UTC first, then India (IST) and New
York (EST/EDT).** Today the page and the report show one zone, the shared `time-zone` setting (Asia/Kolkata on the
user's machines), so people in other places convert in their heads.

Decided with the user:

| Question | Decision |
|---|---|
| Which day is "today" (the page, a daily report) | The **UTC** day, 00:00–24:00 UTC, matching the first time shown |
| New York | Its **real local time**: EST in winter, EDT in summer, labelled accordingly |

## What Changes

| Step | In plain words |
|---|---|
| **Display zones are a setting** | `anomaly.shared.display-time-zones` (default `[UTC, Asia/Kolkata, America/New_York]`): the first is the main one, the others are added after it. Any valid zones, any order, 1 to 4 of them |
| **Every time on the page in all of them** | Incident start and end, "since", the closed list, line history, and (next changes) reasons and reports: `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`. Short labels come from each zone's own name for that date (EST or EDT, IST) |
| **Dates when they differ** | When a zone is on another calendar day, its date is shown with it: `23:10 UTC · 04:40 IST (18 Nov) · 18:10 EST` |
| **"Today" is the UTC day** | The default shared `time-zone` becomes `UTC` (template and `setup.sh`'s default); it still decides the page's day and a daily report's window and can be changed |
| **The report** | Times in the report use the same zones (the report itself is reworked in `add-reports-on-demand`; this change makes its time format the shared one) |
| **Durations unchanged** | "4 min", "2 h 13 min" need no zone |

## How we will know it works

| Check | Expected |
|---|---|
| Three zones | 13:04:10 UTC on 17 Nov shows `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST` |
| Summer | 13:04:10 UTC on 17 Jul shows `… · 09:04:10 EDT` |
| Another day | 23:10 UTC on 17 Nov shows IST with "(18 Nov)" |
| Setting | `[UTC]` shows one zone; `[America/New_York, UTC]` puts New York first; a wrong zone is refused |
| Today | With `time-zone: UTC`, an incident at 23:30 UTC on 16 Nov is not on the 17 Nov page; at 00:10 UTC on 17 Nov it is |
| Report | The same three-zone times in a report |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `runtime-configuration`: the display zones setting; UTC as the default day.
- `live-alerting`: every time on the page in the display zones.
- `incident-reporting`: report times in the display zones.

## Out of scope

| Not in this change | Why |
|---|---|
| A zone picker per viewer | One setting for everyone keeps pages and reports the same; can come later |
| Health and log times | They stay UTC (machine-readable) |

## Impact

- `app/service-kit`: `SharedSettings.displayTimeZones` with its check.
- `app/notifier`: page config sends the zones; `app.js` formats every time with them (cached formatters, one per zone).
- `app/reporter`: one shared time format for the report.
- `config-template/application.yml`, `scripts/setup.sh` (default UTC), README upgrade note (existing `config-repo`s:
  set `time-zone: UTC`, add `display-time-zones`).
