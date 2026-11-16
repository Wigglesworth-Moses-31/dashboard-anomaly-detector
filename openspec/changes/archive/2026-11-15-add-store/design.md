# Design: add-store

## Context

See proposal.md for the why. Facts that shape the design:

- Kafka topics `metric-series` (compacted, key = series id) and `metric-points` (6 partitions, key = series id)
  hold the Collector's messages (`message-contracts`). The live run produced about 775 point messages and 1,200
  finished points per minute for 226 lines: about 1.7 million points a day at lab scale.
- TimescaleDB 2.32 on Postgres 17 runs on port 55432, database and user `anomaly`, schemas `metrics` and
  `incidents`; the password is `PLATFORM_DB_PASSWORD` in `.env`.
- `StoreService` (gRPC, port 9102) already offers `Ping`, `GetHistory` and `GetDaySummary` with empty answers.
- Spring Boot 4.0.9 manages Flyway 11.14.1 and the PostgreSQL driver 42.7.14.
- No Datadog is needed: the Collector's replay mode re-publishes `recordings/run-1` (42 minutes).

## Goals / Non-Goals

**Goals:** store without loss or duplicates; answer history and day-summary questions quickly; keep storage
bounded; show health.
**Non-Goals:** detection, incidents, the daily clean-up itself (Reporter), any change to the message contracts.

## Decisions

### S1. Tables (created by versioned Flyway migrations)
```sql
-- What each line is. Columns for what the Store queries; the full Protobuf for everything else.
CREATE TABLE metrics.series (
  series_id   text PRIMARY KEY,
  metric      text NOT NULL,
  group_tags  jsonb NOT NULL,
  kind        text NOT NULL,
  unit        text NOT NULL,
  query_text  text NOT NULL,
  views       jsonb NOT NULL,
  descriptor  bytea NOT NULL,
  first_seen  timestamptz NOT NULL,
  updated     timestamptz NOT NULL
);
-- The numbers: a hypertable split into one chunk per day.
CREATE TABLE metrics.points (
  series_id text NOT NULL,
  ts        timestamptz NOT NULL,
  value     double precision NOT NULL,
  partial   boolean NOT NULL DEFAULT false,
  PRIMARY KEY (series_id, ts)
);
SELECT create_hypertable('metrics.points', by_range('ts', INTERVAL '1 day'));
-- Per-minute summaries, kept up to date by TimescaleDB (a continuous aggregate).
CREATE MATERIALIZED VIEW metrics.points_1m WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
  SELECT series_id, time_bucket('1 minute', ts) AS minute,
         min(value) AS min, max(value) AS max, avg(value) AS avg, count(*) AS n
  FROM metrics.points GROUP BY series_id, minute WITH NO DATA;
```
- **No foreign key** from points to series: the two topics are read independently, so points may arrive before
  their description (spec: numbers before their description).
- The primary key `(series_id, ts)` is the "stored once" guarantee, and its index serves history questions.
- `materialized_only = false` lets per-minute answers include the last few minutes that are not yet summarised.
- A continuous aggregate cannot be created inside a transaction, so that migration runs outside one
  (Flyway script setting `executeInTransaction=false`). Checked in task 1.2.
**Alternative:** one plain Postgres table with a date index. Simpler, but deleting a day row by row is slow and
per-minute summaries would be computed on every question.

### S2. Reading Kafka and writing in batches (one consumer loop)
```
poll (up to 500 records) → ContractChecks → set broken ones aside (count + log)
     → merge points by (series_id, ts), last value wins → one database transaction:
           upsert descriptions, upsert points
     → commit the Kafka offsets → next poll
```
- **Commit after write:** offsets are committed only after the database transaction commits. A crash re-reads
  the last batch (at least once); the upsert makes re-reading harmless (effectively once).
- **Merge inside a batch** (a hash map keyed by series and time): a multi-row upsert must not touch the same row
  twice, and the latest value is the one to keep.
- **Upsert:** `INSERT ... ON CONFLICT (series_id, ts) DO UPDATE SET value = EXCLUDED.value, partial =
  EXCLUDED.partial WHERE the value or partial flag differs`, so an unchanged repeat writes nothing.
- A description is upserted only if its `updated` time is newer than the stored one.
- **Backpressure:** the loop only polls again after writing, and a poll returns at most 500 records, so a slow
  database slows reading instead of filling memory.
- Plain `KafkaConsumer` (group `store`, auto-commit off), not a framework listener, to keep "commit after write"
  explicit and visible.
**Alternative:** Spring Kafka listeners. Less code, but the commit timing is hidden in configuration.

### S3. Storage limits from settings
At start and on every settings change the Store applies TimescaleDB policies from `config-repo/store.yml`:
- raw points: retention policy at `raw-safety-limit` (default **2 days**, at most 7); the Reporter removes
  reported raw points earlier;
- per-minute summaries: retention policy at `summary-retention` (default **2 days**, at most 7) and a refresh
  policy every minute.
**Why 2 days (user, 15 Nov 2026):** this is a lab system, never shipped to production, and the database must not
fill up. A report covers at most one day, so raw points are needed for at most one day plus a day of margin.
Rough size at lab scale: about 1.7 million raw points a day at 70-90 bytes each with the index, so about 150 MB a
day and about 300 MB at the 2-day maximum; per-minute summaries add about 10 MB.
**Measured (task 7.3, 15 Nov):** 166 bytes per raw point (78 the row, 88 the index: points of 226 lines arrive
interleaved, so the index pages are only partly full) and 174 bytes per per-minute row. That is about 280 MB of raw
points and 57 MB of per-minute summaries a day, about **680 MB at the 2-day maximum**: under the 1 GB alarm, with
about a third to spare. The estimate above was about half the real size.

**Size alarm:** every minute the Store reads the database size (`pg_database_size`, and per table
`hypertable_size` / `pg_total_relation_size`). Above `max-database-size` (default 1 GB) health turns DEGRADED,
naming the total and the largest table, so a stopped clean-up is noticed instead of silently filling the disk.
The database's memory is already capped by its own settings (512 MB); what grows is disk.
Policies are replaced, not added twice (`remove_*_policy(..., if_exists => true)` then `add_*_policy`).
**Why:** limits are settings, not code (spec: runtime-configuration).

### S4. Answering questions (gRPC)
- `GetHistory(series_id, from, to, RAW)`: `SELECT ts, value, partial FROM metrics.points WHERE series_id = ? AND
  ts >= ? AND ts < ? ORDER BY ts`, read with a cursor (fetch size 1000) and sent as chunks of at most 1000 points.
- `GetHistory(..., MINUTE)`: the same from `metrics.points_1m`, value = the minute's average.
- `GetSummary(from, to)` (new, decided with the user: option A): per line over raw points in `[from, to)`:
  `count(*), min, max, avg, percentile_cont(0.95) WITHIN GROUP (ORDER BY value)`, streamed one summary per line.
  Exact p95, possible because raw points are kept until the Reporter has used them.
- `GetDaySummary(date, time_zone)`: a thin shortcut: computes that day's midnight-to-midnight in the time zone
  (handles 23- and 25-hour days where clocks change) and runs exactly the same query as `GetSummary`.
  **Why:** a day is just one window; building the general question once means a 6-hour or 3-day report is a
  Reporter setting later, never a Store change.
- Ranges longer than 31 days, a reversed range, or an unspecified resolution are refused with `INVALID_ARGUMENT`.

### S4b. The contract addition
```proto
service StoreService {
  ...existing Ping, GetHistory, GetDaySummary unchanged...
  rpc GetSummary (SummaryRequest) returns (stream SeriesSummary);   // any window
}
message SummaryRequest { int64 from_ms = 1; int64 to_ms = 2; }        // [from, to), UTC milliseconds
message SeriesSummary  { string series_id = 1; int64 count = 2; double min = 3; double max = 4;
                         double mean = 5; double p95 = 6; int64 from_ms = 7; int64 to_ms = 8; }
```
Only additions, so `ContractCompatibilityTest` passes against the old baseline before the baseline is refreshed
on purpose; `docs/contracts.md` gains the method.

### S5. Settings (config-repo/store.yml, validated, live-reloaded)
| Setting | Default | Meaning |
|---|---|---|
| `max-batch-records` | 500 | Records per poll and per database write |
| `poll-timeout` | 200ms | How long a poll waits for records |
| `raw-safety-limit` | 2d (at most 7d) | Raw points are never kept longer |
| `summary-retention` | 2d (at most 7d) | Per-minute summaries kept |
| `max-database-size` | 1GB | Above this, health is DEGRADED (size alarm) |
| `size-check-interval` | 1m | How often the size is read |
| `history-max-range` | 31d | Longest range one history or summary question may ask |
| `spring.datasource.*` | port 55432, user `anomaly` | Password from the environment (`PLATFORM_DB_PASSWORD`) |
The existing `raw-retention: 2d` becomes the Reporter's setting in `add-reporter`; it is left untouched here.

### S6. Health
Details: Kafka lag (messages waiting, per topic), last save, points and descriptions saved, messages set aside,
write delay p50/p95 (time from poll to commit). DEGRADED if the lag keeps growing for a minute, the last save
failed, or the size alarm is on. The write delays of the last 1000 batches live in a **ring buffer**; percentiles are computed when the
health page is read.

### S7. Data structures, algorithms and patterns
| Problem | Choice | Cost |
|---|---|---|
| Fewer round trips to the database | **Batching** (one transaction per poll of ≤ 500 records) | O(1) amortised per point |
| Same point twice in one batch | **Hash map** keyed by (series, time), last wins | O(n) per batch |
| Same point across batches or after a crash | **Idempotent upsert** on the primary key (B-tree) | O(log n) per point |
| Never lose a point | **Commit after write** (at least once) + idempotency = effectively once | - |
| Slow database must not fill memory | **Backpressure** by bounded polls | Memory O(batch) |
| Never let the database grow unnoticed | **Size alarm** (periodic size check against a limit) | One small query a minute |
| Delete a whole day fast; read only the days asked | **Time partitioning** (hypertable chunks per day) | Drop a chunk instead of row deletes |
| Per-minute answers without scanning raw points | **Continuous aggregate** (incrementally maintained materialised view) | O(minutes) instead of O(points) |
| Long answers with little memory | **Cursor + streaming in chunks** of ≤ 1000 | Memory O(chunk) |
| Write-delay percentiles | **Ring buffer** of the last 1000 delays | O(1) add, O(k log k) on read |
| Keep SQL in one place per table | **Repository** (`SeriesRepository`, `PointRepository`) | - |
| One batch = one unit of work | **Unit of Work** (single transaction, then offsets) | - |

## Risks / Trade-offs

- [Continuous aggregate needs features of the TimescaleDB image's licence] → checked in task 1.1 before relying on it.
- [Kafka holds older live-run messages] → reading from the earliest offset is a free backlog test (33,000+ messages).
- [p95 over a whole day of raw points per line is heavy at org scale] → fine for 226 lines; at scale use a
  percentile sketch per minute (noted for later).
- [The database fills the disk if clean-up stops] → 2-day limits enforced by the database itself, plus the size
  alarm; validators refuse limits above 7 days.
- [The Detector wants a longer baseline] → it gets at most 2 days of per-minute summaries; decided in add-detector.
- [A broken message is only logged] → acceptable now; a dead-letter topic can be added without changing other services.
- [Multi-row upsert must not touch a row twice] → handled by the in-batch merge (S2).
- [Per-minute answers miss points that arrive more than 3 hours late] → the refresh policy summarises only the
  last 3 hours; older minutes are read from what was already summarised. The Collector only sends the last few
  minutes, so this does not happen in practice (found while testing, 15 Nov).

## Migration Plan

New tables only (schema `metrics` was empty). Flyway records applied migrations in its own history table; a fresh
database gets everything on the Store's first start. Removing: stop the Store; `DROP SCHEMA metrics CASCADE` deletes
the data (only on purpose).

## Open Questions

None that change the plan.
