# Proposal: fix-backfill-faults

## Why

On 21 Nov 2026 the user's second machine ran a fresh start with the 24-hour backfill. Three things broke:

| # | What the user saw | Cause (checked) |
|---|---|---|
| 1 | The Store failed to refresh the per-minute summaries for backfilled times | `StorageHousekeeping.refreshLate()` sends `CALL refresh_continuous_aggregate('metrics.points_1m', ?, ?)`. The procedure takes "any" types, so PostgreSQL cannot tell what `?` is and rejects the call. The late range was already taken out of `StoreStats`, so it is lost, not retried. |
| 2 | The Detector stopped reading Kafka with about 237,000 messages waiting | **A memory leak in `Window`** (the sliding median). Values that leave the 30-minute window are only marked "delayed"; they are dropped from the heap only when they reach its top. Measured: after 24 hours of 10-second values a line keeps **8,640 heap entries and 8,460 delayed marks instead of 180**, 48 times too many. It grows in live running too; the backfill only got there faster. Each service also has only 256 MB (`scripts/system.sh`). |
| 3 | The Success Rate panel showed `gateway.request.count` and sat near 0%, then jumped to 100% | A formula line is labelled with the metric underneath it, not with the formula or its alias. Whether the 0% values are also wrong will be checked against that panel's JSON on the user's machine. |

The user also found that `git pull` does not change an existing `config-repo`, so that machine still runs old
settings (2-day raw limit, 1 GB alarm, 2-day backfill).

## What Changes

| What | How |
|---|---|
| **Store refresh** | Cast the times: `CALL refresh_continuous_aggregate('metrics.points_1m', ?::timestamptz, ?::timestamptz)`. If the refresh fails, the range goes back to `StoreStats` and is tried again on the next run. |
| **Detector window** | Keep the window's heaps clean: when the delayed marks are more than the live values, rebuild both heaps from the 180 live values (O(w), rare). Memory per line then stays at about 2 × the window, whatever the run time. |
| **Detector memory** | Raise each Java service's limit from 256 MB to 512 MB in `scripts/system.sh`, and log the Detector's line count and heap use each minute so the next leak shows early. |
| **Formula labels** | A line computed by a formula is named by the formula's alias (`alias` in the widget JSON), else the formula text (`100 * (1 - query2 / query1)`), never the bare metric. The metric name stays in the side panel's details. |
| **Success Rate values** | Compare our values with Datadog's for the same panel and the same 15 minutes, and fix what differs. |
| **Old settings warning** | At start, `scripts/system.sh` lists each setting where `config-repo` differs from `config-template` for keys the templates changed, and says how to update. It never changes `config-repo` itself. |

## Not in this change

The redesign (bigger graphs, colours, navigation, ranked anomaly strip on each dashboard, show only one line,
automatic catch-up after a shutdown) comes next as its own change.

The Collector's settle-delay warnings: the user will paste the exact warning lines, and the fix joins this
change if it is a code problem, or becomes a setting change if Datadog is simply slow.

## Capabilities

### Modified Capabilities
- `metric-storage`: a late-value refresh that fails is retried.
- `anomaly-detection`: a line's memory stays bounded by its window.
- `dashboard-view`: formula lines are named by their alias or formula.

## Impact

`app/store` (`StorageHousekeeping`, `StoreStats`), `app/detector` (`Window`, stats log), `app/notifier` static
page and line naming, `scripts/system.sh`. No message contracts change. No data wipe is needed.
