# Design: add-collector

## Context

See proposal.md for the why. Facts that shape the design:

- The Collector is a running skeleton: settings snapshot (`CollectorSettings`), Datadog keys from the
  environment only (`DatadogCredentials`), health and settings pages. Contracts are fixed (`message-contracts`):
  `SeriesDescriptor`, `MetricPoints`, `SeriesIds`, `ContractChecks`, `Topics`.
- Datadog, measured on the personal trial on 15 Nov 2026:
  - `POST /api/v2/query/timeseries` accepts several queries **and formulas** in one request, with
    `interval` in ms. It returns one series per formula (`query_index`), `group_tags` like `api:/v1/devices`,
    one shared `times` list and one `values` list per series, with `null` for a missing step.
    Rate limit `query_timeseries_public`: **300 requests per 10 seconds**, reported in `x-ratelimit-*` headers.
  - `GET /api/v1/dashboard/{id}` returns the definition; `GET /api/v1/metrics/{name}` returns type and unit.
- The two lab dashboards hold 48 metric queries and 41 formulas across groups, split groups, timeseries,
  tables, a toplist, query values, a sunburst and a service map (which has no metric query).

## Goals / Non-Goals

**Goals:** discover every panel; poll them on time within Datadog's limits; produce correct, deduplicated
messages; isolate failures; record and replay.
**Non-Goals:** storing or analysing numbers; any write to Datadog; other sources.

## Decisions

### K1. The parts, and how one poll flows
```
           settings (config-repo/collector.yml, live reload)
                │
 DashboardReader ──► PollPlan (one PollUnit per panel request, duplicates merged)
                │
            Scheduler (min-heap by next-due time) ──► Fetcher (virtual threads, bulkhead, retries,
                                                         │      circuit breaker, rate limiter)
                                                         ▼
                                   MetricSource  ◄── RecordingSource (decorator, optional)
                                   ├─ DatadogSource  (HTTP to Datadog)
                                   └─ ReplaySource   (recorded files, no network)
                                                         │ TimeseriesAnswer
                                                         ▼
                              AnswerTranslator ──► Classifier (rules → metadata cache → unknown)
                                                         │
                                                         ▼
                              Emitter (descriptor cache, per-line recent steps) ──► KafkaPublisher
                                                                   metric-series, metric-points
```
Each part has one job and is tested on its own; only `DatadogSource` knows Datadog's URLs and JSON.

### K2. Ports and Adapters, with Decorator for recording
`MetricSource` is the port: `dashboard(id)`, `timeseries(request)`, `metadata(metricName)`.
Adapters: `DatadogSource` (Java's own `HttpClient`, no extra library) and `ReplaySource` (files).
`RecordingSource` wraps any source and writes each raw answer to `recordings/` as it passes through
(**Decorator**). Setting `anomaly.collector.source` picks `datadog` or `replay`; `recording.enabled` turns on the
decorator. Keys are added to request headers inside `DatadogSource` only; recordings never contain headers.
**Why:** tests and replay need no Datadog; a future Prometheus adapter changes nothing else.

### K3. Discovery: a tree walk, then merge duplicates
A dashboard is a tree: groups and split groups contain widgets. An iterative **depth-first walk** (an explicit
stack, so a deep dashboard cannot overflow the call stack) visits every widget once, O(W). Each widget request
that has metric queries becomes a `PollUnit` (its queries plus formulas). Widgets without metric queries
(service map, notes) are skipped and counted in the logs.
The same request can appear on several panels or dashboards. A **hash map** keyed by the request's canonical
form (after filters are filled in) merges them into one `PollUnit` with several views: one Datadog call, many
panels, O(1) per lookup.

### K4. Filling in dashboard filters
Each query's filter `{...}` is split into its comma-separated items. An item `$name` (or `$name.value`) is
replaced by `<prefix>:<value>`, where `prefix` comes from the dashboard's template variable and `value` from
settings (`anomaly.collector.dashboard-filters.<dashboard-id>.<name>`), else the dashboard default. A `*` value
drops the item; an empty filter becomes `{*}`.
**Why item by item:** plain text replacement leaves stray commas (`{env:prod,,}`) and would also replace
`$name` inside tag values.

### K5. Asking for peaks
For each query: if it already has `.rollup(...)`, it is kept. Otherwise `.rollup(max, <step>)` is added, or
`.rollup(sum, <step>)` when the query ends in `.as_count()` (a count's step value is its total). The request sends
`interval = step` and a window aligned to step boundaries: on start `now - backfill`, then `now - recent-window`.
**Checked on the real API (task 3.2, 15 Nov 2026), same 2-minute window:**
| Query | Result |
|---|---|
| `sum:...as_count()` | 1062 requests in total |
| `sum:...as_count().rollup(sum, 10)` | 1062: accepted, identical (true counts per step) |
| `sum:...rollup(sum, 10)` without `.as_count()` | 106.2: **ten times too small** (it adds per-second rates). The planner must never add `rollup(sum)` without `.as_count()` |
| gauge and p95, with and without `.rollup(max, 10)` | identical |
So at a 10-second step Datadog already holds one value per step for these metrics, and `max` changes nothing. What
keeps spikes visible is **asking for 10-second steps at all** (long-range graphs and monitors average many minutes
into one point). `rollup(max, step)` stays as a cheap safeguard for a larger step set in settings.
A query wrapped in a function (for example `top(...)`) is left unchanged, because appending would break it.

### K6. Turning an answer into messages
For each returned series: `query_id` = SeriesIds hash of the unit's canonical request plus the formula;
`group_tags` from `k:v` items; `series_id` from SeriesIds. A step `[t, t + step)` whose end is after the
moment the answer arrived is **partial**. `null` values produce no point (spec: gaps are left out).
`metric` is the query's metric name, or the distinct names joined with `, ` for a formula over several queries.

### K7. Sending each finished step once
Per metric line, the Emitter keeps the recent steps it already sent as finished: a **bounded map** of
`ts → value` (a `LinkedHashMap` that drops its eldest entry beyond the backfill window, so memory per line is
fixed: 60 entries at 10 s for 10 minutes). A point is sent if it is partial, or finished and (not sent before, or
its value changed). Descriptions are sent when a line is new or its description changed, tracked in a hash map
`series_id → hash of the description`.
**Why:** overlapping windows return the same finished steps every 15 seconds; this keeps Kafka traffic to what
is new.

### K8. Kind and unit: three layers
1. **Settings rules** (`anomaly.collector.classification`): first match wins; match on metric name pattern,
   panel title pattern, or `has-formula`; set kind and/or unit.
2. **Datadog metadata** (`/api/v1/metrics/{name}`): count and rate → count, gauge → gauge, distribution with a
   time unit → latency. Cached per metric in a size-bounded **LRU cache** with a time-to-live (default 24 h),
   so each metric is looked up about once a day.
3. **Default**: `KIND_UNKNOWN`. `classified_by` records which layer decided (`rule:<n>`, `metadata`, `default`).

### K9. Scheduling: a min-heap of due times
Every `PollUnit` has a next-due time. A **priority queue (min-heap)** returns the soonest one in O(log n). One
scheduler thread waits until the earliest is due, hands it to a worker, and reschedules it at
`due + poll-interval`. First due times are spread across the interval by a hash of the unit (**jitter**), so
200 panels do not all ask at the same second. If a unit's previous poll is still running, that turn is skipped
instead of piling up.

### K10. Being polite to Datadog
- **Token bucket** (O(1) per request): capacity and refill come from `x-ratelimit-limit` / `x-ratelimit-period`,
  starting at 80% of the measured 300 per 10 s to leave room for people using the same account.
- On `429`: a shared **gate** closes until `x-ratelimit-reset`; every worker waits on it.
- **Bulkhead**: a semaphore limits concurrent requests (default 8), so one slow answer cannot use every
  connection.
- **Retries** on timeouts and 5xx: up to 3 tries with **exponential backoff and full jitter** (random wait between
  0 and `min(8 s, 0.5 s × 2^try)`). A 400 (bad query) is not retried.
- **Circuit breaker per PollUnit**: after 5 failures in a row it opens for 5 minutes (no calls), then lets one try
  through. The health page lists open breakers by panel.
- **Virtual threads** (Java 25) for every HTTP call: waiting on the network costs no platform thread.

### K11. Publishing to Kafka
One `KafkaProducer<String, byte[]>` with idempotence on and `acks=all` (no duplicates from the producer's own
retries, nothing lost if the broker restarts), a short linger for batching. Key from `Topics.key(...)`; header
`contract` = message type name. Every message passes `ContractChecks` before sending; a failing one is logged
and dropped (it would indicate a bug in the Collector).

### K12. Recording and replay
`RecordingSource` appends JSON lines to `recordings/<yyyy-mm-dd>/{dashboards,timeseries,metadata}.jsonl`:
the request (without keys), the answer as returned by the source port, and the time. The Collector also writes the messages it publishes to
`published.jsonl` when recording. `ReplaySource` answers each request with the next recorded answer for the same
canonical request, and a replay clock follows the recorded times, so a replay is deterministic.

### K13. New settings (all in `config-repo/collector.yml`, validated, live-reloaded)
| Setting | Default | Meaning |
|---|---|---|
| `dashboard-filters.<id>.<name>` | dashboard default | Value for a dashboard placeholder |
| `classification` | lab rules | Kind/unit rules (K8) |
| `max-concurrent-requests` | 8 | Bulkhead size |
| `rate-limit-share` | 0.8 | Share of Datadog's reported limit to use |
| `retries`, `retry-base`, `retry-cap` | 3, 500ms, 8s | Retry policy |
| `breaker-failures`, `breaker-open` | 5, 5m | Circuit breaker |
| `metadata-ttl`, `metadata-cache-size` | 24h, 10000 | Metadata cache |
| `recording.enabled`, `recording.dir` | false, recordings | Recording |
| `source`, `replay-dir` | datadog, - | Live or replay |

### K14. Data structures, algorithms and patterns
| Problem | Choice | Cost |
|---|---|---|
| Datadog must be swappable; tests need no network | **Ports and Adapters** (`MetricSource`) | - |
| Save answers without touching the source code | **Decorator** (`RecordingSource`) | - |
| Visit every widget in nested dashboards | **Iterative depth-first walk** with a stack | O(W) |
| Same request on many panels | **Hash map** by canonical request | O(1) per lookup |
| Poll hundreds of units on time | **Min-heap** priority queue by due time | O(log n) per schedule |
| Don't start all polls at once | **Jitter** from a hash of the unit | O(1) |
| Stay under Datadog's limit | **Token bucket** + shared **gate** on 429 | O(1) |
| One slow call must not block all | **Bulkhead** (semaphore) | O(1) |
| Transient errors | **Exponential backoff with full jitter** | - |
| A panel that keeps failing | **Circuit breaker** (closed → open → half-open state machine) | O(1) |
| Waiting on the network cheaply | **Virtual threads** | - |
| Don't resend finished steps | **Bounded LinkedHashMap** per line (eldest dropped) | O(1), fixed memory |
| Look up metadata once a day | **LRU cache with time-to-live** | O(1) |
| Kind and unit from several sources | **Chain of Responsibility** (rules → metadata → default) | O(rules) |
| Building the request and messages | **Builder** | - |

## Risks / Trade-offs

- [Datadog changes its dashboard JSON] → tolerant parsing: unknown widget types are skipped and counted, not
  fatal; recorded dashboards make the reader testable.
- [`.rollup` together with `.as_count()` behaves differently than expected] → checked with the real API in
  task 3.2 before relying on it.
- [Datadog delivers late data for recent steps] → each poll re-reads the last 2 minutes; a changed finished step
  is sent again (K7).
- [A formula changes on a dashboard] → its `query_id` changes, so it becomes a new line; the old line simply
  stops. Acceptable: the panel really changed.
- [The trial ends ~26 Nov] → record during live verification (task 10); later work uses replay.
- [Rate limits shared with other users of a larger account] → `rate-limit-share` below 1, and every request counted in the logs.

## Migration Plan

New behaviour in an existing service. Turning it off: set `source` to `replay` with an empty directory, or stop
the Collector. Nothing is written to Datadog, so nothing needs undoing there.

## Open Questions

None that change the plan. Default numbers (concurrency, retries, breaker) are settings and can be tuned later.
