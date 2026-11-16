# Proposal: add-store

## Why

The Collector now puts real numbers on Kafka, but nothing keeps them: Kafka holds them only 2 days and cannot
answer questions such as "the last 7 days of this line, per minute". The Detector needs that history to know what
is normal, and the Reporter needs each day's summary. The Store keeps every number safely and answers those
questions. It needs no Datadog: the 42-minute recording can feed Kafka in replay mode.

## What Changes

The Store goes from an empty skeleton to a working service:

| Step | In plain words |
|---|---|
| **Read from Kafka** | Take every line description (`metric-series`) and every batch of numbers (`metric-points`) |
| **Check before saving** | Run the shared contract checks; a broken message is counted and set aside, never saved |
| **Save in batches** | Collect a few hundred points, then write them in one go; each point is saved once, with its latest value |
| **Never lose a number** | Tell Kafka "done" only after the database has saved it, so a crash means "read again", never "lost" |
| **Keep summaries** | The database keeps per-minute summaries (lowest, highest, average, count) automatically |
| **Answer questions (gRPC)** | `GetHistory`: one line's numbers for a time range, raw or per minute. `GetSummary`: every line's summary for **any time window** (6 hours, a day, 3 days). `GetDaySummary`: a shortcut for one calendar day in a time zone |
| **Keep storage small** | Lab system, not production (user, 15 Nov): raw 10-second points and per-minute summaries are kept **at most 2 days** (a report covers at most one day, plus one day of margin); the Reporter removes reported raw points earlier. A **size alarm** (default 1 GB) turns health DEGRADED if the database grows anyway |
| **Show its state** | Health: how far behind Kafka it is, last write, rows saved, messages set aside |

## Capabilities

### New Capabilities
- `metric-storage`: keeping every metric line and number from the Collector, without loss or duplicates, and
  answering history and day-summary questions.

### Modified Capabilities
None at spec level. The shared contract gains one new question, `StoreService.GetSummary(from, to)` with its
messages: an addition, which the compatibility guard allows; nothing existing changes. Decided with the user on
15 Nov 2026 (option A), so a report over any window, such as 6 hours, needs only a Reporter setting later.

## Out of scope

| Not in this change | Where |
|---|---|
| Deciding what is unusual | `add-detector` |
| Daily report and the daily clean-up of raw points | `add-reporter` (the Store only offers the safety limit) |
| Incident tables | `add-detector` |
| A browser view of history | `add-notifier` |

## Impact

- `app/contracts`: `store_service.proto` gains `GetSummary`, `SummaryRequest`, `SeriesSummary`; baseline refreshed on purpose.
- `app/store`: Kafka consumer, batch writer, repositories, gRPC answers, health, tests.
- Database `anomaly` (TimescaleDB): schema `metrics` gets its tables through versioned migrations (Flyway).
- `config-repo/store.yml`: new settings (batch size, wait, retention, safety limit). No code change to tune them.
- `scripts/system.sh`: the Store receives the database password from `.env` (like the Collector gets the
  Datadog keys); it never appears in settings or logs.
- New dependencies: Spring JDBC, Flyway 11, PostgreSQL driver.
- Testing uses the Collector's replay of `recordings/run-1`, so no Datadog is needed.
