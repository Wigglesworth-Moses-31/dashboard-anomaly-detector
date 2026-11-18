# Design: keep-ten-days

## Decisions

### T1. The Store: compressed, removed by age (TimescaleDB)

| Table | Chunks | Compression | Removal |
|---|---|---|---|
| `metrics.points` (raw) | 6 hours for new chunks | after 6 hours; segment by `series_id`, order by `ts` (one line's values side by side compress well) | `add_retention_policy` 10 days: whole chunks dropped |
| `points_1m` (per minute, continuous aggregate) | as created | after 1 day | 10 days |

Migration `V3`. The Store's own clean-up job and the size alarm stay; its health shows each table's size before and
after compression (`hypertable_compression_stats`). Settings `raw-retention`, `summary-retention`: default 10 days,
at most 10; `history-max-range` 10 days.

### T2. Reports remove nothing

The Reporter stops calling `RemoveRawBefore` after a report (the call stays for operators); catch-up up to 10 days.

### T3. Kafka

`create-topics.sh`: `retention.ms` 10 days for every topic, also applied to existing topics (`kafka-configs --alter`).
Producers (Collector, Detector, Notifier commands) set `compression.type=zstd`.

### T4. Detector, Notifier, page

Detector `incident-retention` default 10 days, at most 10. Notifier: a line's anomaly index keeps 10 days and at most
200 entries per line (oldest dropped). Page ranges add `10d`; ranges up to 10 days allowed.

## Migration Plan

Deploy everything; `V3` runs on the Store's start; existing data is compressed by the policy within hours; topics
are altered in place by `scripts/system.sh start`.
