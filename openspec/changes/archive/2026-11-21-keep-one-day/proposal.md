# Proposal: keep-one-day

## Why

The user (21 Nov 2026): "hold only the last 24 hours of metrics alone; when started new, we bring the last 24 hours
alone, and retention also stays the same, 24 hours". Asked whether anomalies and reasons follow: **everything 24
hours**. This replaces the 10 days of `keep-ten-days` (18 Nov) and the 2-day backfill of `add-backfill`.

## What Changes

| What | Was | Now |
|---|---|---|
| Raw 10-second values | 10 days | **24 hours**, removed by age |
| Per-minute summaries | 10 days | **24 hours** |
| Closed anomalies and their reasons | 10 days after closing | **24 hours** after closing. Open ones and ones waiting for a reason stay until they close (removing them would lose a reason still to be given) |
| Kafka messages (points, anomalies, commands) | 10 days | **24 hours** (anomalies still waiting are re-announced every 12 hours, so a restarted Notifier still knows them) |
| A new dashboard's past | last 2 days | **last 24 hours** (6 calls of 4 hours per batch of 40 panels) |
| Reports: missed windows caught up | up to 10 days | up to 24 hours |
| Dashboard time ranges | 15m, 1h, 4h, 1d, 7d, 10d | **15m, 30m, 1h, 3h, 6h, 12h, 1d** with an auto interval (set in `add-datadog-look`) |
| A metric's anomaly list | 10 days | 24 hours |
| Limits | at most 10 days | Defaults 24 hours; a setting may still go up to 10 days if wanted later (no code change). The page's ranges (15m to 1d, auto interval) are set in `add-datadog-look`, which rewrites the pages |
| Compression | raw after 6 h, per minute after 3 days | **Kept for raw values** (after 6 h); per-minute values are small and live less than a day, so not compressed |

Size after 24 hours on this machine: raw ≈ 400 MB uncompressed → about 100 MB compressed; far under the 1 GB alarm.

## How we will know it works

| Check | Expected |
|---|---|
| Defaults | Fresh setup: every retention 24 hours; Kafka topics `retention.ms` = 1 day |
| Existing machine | Topics changed in place; the Store applies 24 hours without restart once `config-repo` says so (upgrade note) |
| Age | Values and closed anomalies older than 24 hours are gone; one waiting for a reason stays |
| Backfill | A new dashboard pulls 24 hours (6 windows), not 2 days |
| Ranges | The page offers 15m, 30m, 1h, 3h, 6h, 12h, 1d (built in add-datadog-look) |

## Capabilities

### Modified Capabilities
- `metric-storage`: values kept 24 hours by default; compression for raw values.
- `anomaly-detection`: closed anomalies kept 24 hours by default.
- `message-contracts`: topics keep 24 hours.
- `metric-collection`: a new view's backfill is its last 24 hours.
- `incident-reporting`: catch-up up to 24 hours.

## Impact

Settings defaults and templates (`store.yml`, `detector.yml`, `reporter.yml`, `collector.yml`, `notifier.yml`),
`platform/create-topics.sh` (1 day, applied in place), the Notifier's line index window, the page's ranges, tests,
README (storage table, upgrade note). No new code paths.
