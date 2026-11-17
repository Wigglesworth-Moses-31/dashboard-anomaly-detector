# Verification: add-display-time-zones

17 Nov 2026. Every time in UTC first, then India and New York; "today" is the UTC day by default.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **249 run, 0 failed**, 23 skipped (live-only) | |
| `TimesTest` (service-kit) | 2 passed | `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST` (17 Nov), `… · 09:04:10 EDT` (17 Jul), `23:10 UTC · 04:40 IST (18 Nov) · 18:10 EST`; one zone; default zones; a misspelt zone, an empty list and five zones refused |
| `HubTest` (Notifier) | passed | The snapshot's end line carries `display_zones`; a changed list travels on it |
| `IncidentReportTest` (Reporter) | passed | Incident times in three zones (`11:26:00 UTC · 16:56:00 IST · 06:26:00 EST` for 16:56 IST); the header keeps the day's own clock ("Asia/Kolkata (00:00–24:00)") and adds a `Window:` line in all zones |

## The page's formatting code, run in the JavaScript engine

| Input | Output |
|---|---|
| 17 Nov 13:04:10 UTC | `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST` |
| 17 Jul 13:04:10 UTC | `13:04:10 UTC · 18:34:10 IST · 09:04:10 EDT` |
| 17 Nov 23:10 UTC (minutes) | `23:10 UTC · 04:40 IST (18 Nov) · 18:10 EST` |
| Zones `[UTC]` | `13:04:10 UTC` |
| Zones `[America/New_York, UTC]` | `08:04:10 EST · 13:04:10 UTC` |
| 600 times | 3 ms |

Found while designing: the browser names India "IST" only in Indian English ("GMT+5:30" in US English) and New York
"EST/EDT" only in US English, so each zone takes the first locale with a real abbreviation.

## Live (the running system, the incidents of 15 Nov)

The page shows `since 08:42:10 UTC · 14:12:10 IST · 03:42:10 EST · 53 h 14 min`; line history entries use the same
form. The local `config-repo` still has `time-zone: Asia/Kolkata` (the day), as expected for an existing installation
until the README's upgrade note is applied; the display zones came from the default.

## Setup

A clean copy: `setup.sh` without `TIME_ZONE` writes `time-zone: UTC` and the three display zones, and no longer asks.
