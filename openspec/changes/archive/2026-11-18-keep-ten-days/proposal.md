# Proposal: keep-ten-days

## Why

The user asked (18 Nov 2026) to **keep everything for 10 days**, "memory efficient and storage efficient", and later the
same day to **extend it to 10 days in total** (this change was `keep-one-week` until then). Today
most data lives 2 days, and raw values even less:

| Kept today | How long | Size now (this machine, 452 lines) |
|---|---|---|
| Raw 10-second values | Removed after each report (last 3 h stay), never more than 2 days | ~0 (cleaned after the last report) |
| Per-minute summaries | 2 days | 4 MB (22 thousand rows) |
| Incidents and their reasons | 2 days after closing (open and calm: until closed) | 0.4 MB |
| A line's history on the page | 2 days | memory, a few hundred entries |
| Kafka messages | 2 days | 135 MB |

Kept 10 days **as they are stored today**, raw values alone would be about 0.9 MB per line per day: 452 lines × 10
days ≈ **4 GB**, and Kafka ≈ 670 MB. That passes the 1 GB size alarm and is the disk bottleneck found earlier
(10 dashboards × 2 regions). So this change does two things together: keep 10 days, and **store it compressed**.
(It absorbs the planned `add-compression`.)

## What Changes

| What | Change | Why it stays small |
|---|---|---|
| **Raw 10-second values** | Kept **10 days**, removed by age. The Reporter no longer removes them after a report | Older data is **compressed by TimescaleDB** (column-wise, per line, in time order): values of one line change little, so they shrink about 10–20× (to be measured). Removing by age drops whole time slices: no row-by-row deletes |
| **Per-minute summaries** | Kept **10 days** | Compressed the same way |
| **Incidents and reasons** | Closed incidents kept **10 days** | Already small (protobuf, one row per incident) |
| **A line's history** | The page shows the last **10 days** of a line's incidents | Memory stays bounded: a fixed number of entries per line, oldest dropped first |
| **Kafka** | Every topic kept **10 days** | Messages **compressed (zstd)** by the producers: about 4× smaller (to be measured) |
| **Reports** | A missed day can be caught up as far back as **10 days** | The data is there now |
| **Limits** | 10 days is the maximum of every retention setting (lab system); the 1 GB size alarm stays | The alarm still catches clean-up that stopped |
| **Memory** | Unchanged: Kafka's heap, database memory and service heaps stay as they are | Compression reads and writes smaller pages; nothing extra is held in memory |

Expected size after 10 days on this machine (to be confirmed by measuring): raw ≈ 200–400 MB, summaries ≈ 15 MB,
Kafka ≈ 170 MB, so under the 1 GB alarm. `add-backfill` adds 2 days per newly onboarded dashboard, inside the same 10.

## How we will know it works

| Check | Expected |
|---|---|
| Raw values stay | After a report, raw values of that day are still there; values older than 10 days are gone |
| Compression | Raw and per-minute data older than the newest few hours are compressed; the compressed size is measured and written down (ratio per table) |
| Questions still answered | A 7-day history question for one line returns the same values compressed as before compressing |
| Detector restart | Still warm from the newest raw values (they are not compressed yet, or read the same when they are) |
| Kafka | Topics show `retention.ms` = 10 days; producers send zstd; bytes on disk per message measured before and after |
| Incidents | A closed incident 9 days old is still on the page's history; 11 days old is gone |
| Line history | "In the last 10 days (n)" on the page |
| Settings | Any retention above 10 days is refused; the defaults are 10 days |
| Upgrade | An existing machine upgrades by starting with the new build: topics are reconfigured in place, existing data is compressed on schedule, nothing is lost |

## Capabilities

### Modified Capabilities
- `metric-storage`: raw values and per-minute summaries kept 10 days, removed by age; older data compressed; the
  "removed on request" clean-up is no longer used by the Reporter.
- `incident-reporting`: the Reporter no longer removes raw values after a report; catch-up up to 10 days.
- `anomaly-detection`: closed incidents kept 10 days by default; waiting incidents re-announced so readers rebuilding
  from 10 days of events still know them.
- `dashboard-view`: a 10d range; ranges up to 10 days allowed; a metric page lists 10 days of anomalies (bounded memory per line).
- `message-contracts`: every topic kept 10 days; messages compressed.

## Out of scope

| Not in this change | Why |
|---|---|
| Keeping data longer than 10 days | Lab system: 10 days is the cap |
| Moving old data to cheaper storage (files, S3) | Not needed at this size |
| Read replicas, Redis | Not needed (decided earlier) |

## Impact

- `app/store`: a migration that turns on compression and age-based removal for raw values and per-minute summaries;
  settings defaults and limits; the size alarm unchanged.
- `app/reporter`: no raw clean-up after the report; catch-up 10 days.
- `app/detector`: incident retention default 10 days.
- `app/notifier`: line history window 10 days; the 10d range; ranges up to 10 days.
- `app/store`: `history-max-range` 10 days.
- Producers (Collector, Detector, Notifier): zstd compression.
- `platform/create-topics.sh`: 7-day retention, applied to existing topics too.
- `config-template/*.yml`, README (storage table, upgrade note).
