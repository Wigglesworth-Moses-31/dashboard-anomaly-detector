# Verification: keep-ten-days

18 Nov 2026. Everything is kept 10 days (asked first as 7, then 10), stored compressed.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **299 run, 0 failed**, 23 skipped (live-only) | |
| Store, Detector, Reporter settings | passed | 10 days allowed, 11 or 30 refused with "at most 10 days" |
| Reporter `ReportJobTest` | passed | A verified report removes nothing (no Store call); the state still ends "cleaned"; restarts write no second report |
| Notifier `LineHistoryTest` | passed | 10-day window; a line keeps at most 200 incidents, oldest dropped first |

## Live (this machine)

| Check | Result |
|---|---|
| Kafka | `metric-points` (and the incident topics) altered in place to `retention.ms=864000000` (10 days) by `scripts/system.sh start`; producers send zstd |
| Store policies | "Storage limits applied: raw points 864000 seconds, per-minute summaries 864000 seconds; compressed after 6 hours and 3 days" after this machine's `config-repo` was set to 10 days (one local commit) — applied without a restart |
| Compression | Raw chunks compressed: 760 kB → 184 kB (**4.1×**) on 4,464 points (small sample; larger chunks compress better); reads unchanged |
| Jobs | Retention (10 days, daily) and compression (after 6 hours) jobs scheduled by TimescaleDB |

## Differences from the design

| Design | Built |
|---|---|
| T1 "migration V3" | The compression settings are applied by the Store's housekeeping at start and on every settings change (idempotent, like the retention policies already were) instead of a one-time migration, so a changed setting is applied the same way |
| Late values | The per-minute refresh covers the last 3 hours; values saved for older times (backfill, late data) trigger one refresh of exactly their range |
