# Design: keep-one-day

## Decisions

### O1. Defaults, not new code

| Where | Setting | Was | Now |
|---|---|---|---|
| `StoreSettings` defaults and `store.yml` | `raw-safety-limit`, `summary-retention` | 10d | **1d** (cap stays 10d) |
| `store.yml` | `history-max-range` | 10d | 1d |
| `DetectorSettings`, `detector.yml` | `incident-retention` | 10d | **1d** |
| `ReporterSettings`, `reporter.yml` | `catch-up` | 10d | **1d** |
| `CollectorEngine`, `collector.yml` | `backfill-new-views` | 2d | **1d** (6 windows of 4 h) |
| `create-topics.sh` | `retention.ms` | 10 days | **1 day**, applied in place |
| `LineHistory` | window | 10 days | **1 day** (cap 200 per line stays) |

The page's ranges are set by `add-datadog-look` (15m, 30m, 1h, 3h, 6h, 12h, 1d with an auto interval).

### O2. Compression stays for raw values

Raw values are still compressed after 6 hours (18 of 24 hours compressed). Per-minute values (compressed after 3
days) never reach that age; harmless, left as is.

### O3. Waiting incidents outlive the 24 hours

Open and calm incidents are never removed (unchanged rule); the Detector re-announces them every 12 hours, so 24 hours
of Kafka is enough for the Notifier to rebuild them after a restart.

## Migration Plan

Existing machines: set the values of O1 in `config-repo` (README upgrade note); topics change on the next start.
