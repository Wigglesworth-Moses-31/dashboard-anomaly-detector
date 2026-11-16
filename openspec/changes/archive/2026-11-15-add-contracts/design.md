# Design: add-contracts

## Context

See proposal.md for the why. Current state, from the archived `add-system-architecture` change:

- `app/contracts` generates Java from `.proto` files (Protobuf 4.33.4) and gRPC classes (grpc-java 1.77.1).
  It holds two placeholders: `placeholder.proto` (`ContractsCheck`) and `store_service.proto` (`Ping` only).
- Kafka topics exist: `metric-series` (compacted), `metric-points`, `incident-events`.
- The Notifier serves RSocket over WebSocket with routes `ping` and an empty `incidents`.
- Earlier ideas for the metric event live in `docs/design.md`; this design replaces them.

## Goals / Non-Goals

**Goals:**
- Final first version of every message and every Store question.
- A stable, collision-safe id for each metric line.
- An automatic guard so a contract edit cannot break existing readers.
- Small shared helpers every service will use: ids, message checks, JSON for the browser.

**Non-Goals:**
- Sending or reading these messages in any service (later changes).
- Choosing detection rules or database tables.

## Decisions

### C1. Two messages per metric line: a rare description and frequent numbers
`SeriesDescriptor` (what the line is) is sent when a line is first seen or changes; `MetricPoints` (the
numbers) on every poll, referring to the line by `series_id`.
**Why:** the description is about 300 bytes of text that never changes; repeating it every 15 seconds for
every line would roughly double the traffic and the parsing work. This is the **Flyweight** idea: share the
unchanging part, send only what varies.
**Alternative:** one self-contained message per poll. Simpler to read, but wasteful at hundreds of lines.

### C2. The messages
```proto
// metric_series.proto: what one metric line is. Kafka metric-series, key = series_id, compacted.
message SeriesDescriptor {
  string series_id = 1;                 // 16 hex characters (C3)
  Source source = 2;                    // { system: "datadog", instance: "datadoghq.com" }
  string query_id = 3;                  // 16 hex characters: hash of the normalised query text
  string query_text = 4;                // the source's query; only the Collector interprets it
  string metric = 5;                    // e.g. lab.api.request.count
  map<string, string> group_tags = 6;   // what makes this line different, e.g. api, status_code
  Aggregation space_aggregation = 7;    // how many sources were combined into one line
  Aggregation time_aggregation = 8;     // how each step was built (MAX keeps spikes)
  int32 step_seconds = 9;
  Kind kind = 10;                       // count, rate, gauge, latency, ratio, unknown
  string unit = 11;
  repeated View views = 12;             // every dashboard panel showing the line
  string classified_by = 13;            // "rule:<n>", "metadata" or "default"
  int64 first_seen_ms = 14;
  int64 updated_ms = 15;
}
message Source { string system = 1; string instance = 2; }
message View   { string view_id = 1; string view_title = 2; string panel_title = 3; }
enum Aggregation { AGGREGATION_UNSPECIFIED = 0; AGGREGATION_SUM = 1; AGGREGATION_AVG = 2; AGGREGATION_MIN = 3;
                   AGGREGATION_MAX = 4; AGGREGATION_COUNT = 5; AGGREGATION_LAST = 6; AGGREGATION_P50 = 7;
                   AGGREGATION_P75 = 8; AGGREGATION_P90 = 9; AGGREGATION_P95 = 10; AGGREGATION_P99 = 11; }
enum Kind { KIND_UNSPECIFIED = 0; KIND_UNKNOWN = 1; KIND_COUNT = 2; KIND_RATE = 3; KIND_GAUGE = 4;
            KIND_LATENCY = 5; KIND_RATIO = 6; }

// metric_points.proto: the newest numbers of one line. Kafka metric-points, key = series_id.
message MetricPoints {
  string series_id = 1;
  int64 collected_ms = 2;               // when the Collector received them (to measure delay)
  repeated Point points = 3;            // oldest first; steps with no data are left out
}
message Point {
  int64 ts_ms = 1;                      // start of the step, UTC milliseconds
  double value = 2;
  bool partial = 3;                     // the step had not ended when collected
}

// incident.proto: Kafka incident-events, key = incident_id.
message IncidentEvent {
  string event_id = 1;                  // unique per event (UUID): spot duplicate deliveries
  string incident_id = 2;
  int64 sequence = 3;                   // 1, 2, 3... per incident: keep only the highest seen
  EventType type = 4;                   // opened, updated, closed
  int64 occurred_ms = 5;
  Incident incident = 6;                // full current state after this event
}
message Incident {
  string incident_id = 1;
  IncidentState state = 2;              // open or closed
  string title = 3;                     // e.g. "billing-core: errors and latency"
  int64 started_ms = 4;
  int64 ended_ms = 5;                   // 0 while open
  repeated AffectedSeries series = 6;
  repeated Link links = 7;              // dashboards at the incident's time window
}
message AffectedSeries {
  string series_id = 1; string metric = 2; map<string, string> group_tags = 3;
  double peak_value = 4; int64 peak_ts_ms = 5;
  double normal_value = 6;              // what was normal just before the spike
  string unit = 7;
}
message Link { string label = 1; string url = 2; }
enum EventType     { EVENT_TYPE_UNSPECIFIED = 0; EVENT_TYPE_OPENED = 1; EVENT_TYPE_UPDATED = 2; EVENT_TYPE_CLOSED = 3; }
enum IncidentState { INCIDENT_STATE_UNSPECIFIED = 0; INCIDENT_STATE_OPEN = 1; INCIDENT_STATE_CLOSED = 2; }
```
- Every enum starts with `..._UNSPECIFIED = 0`. Protobuf fills a missing enum with 0, so "not set" is
  always visible instead of silently meaning "SUM" or "OPENED".
- Enum values carry their enum's name as a prefix, because Protobuf enum values share one name space per
  package (`COUNT` could not exist in both `Aggregation` and `Kind`).
- Every file: `package lab.anomaly.v1; option java_package = "lab.anomaly.contracts.v1";`. The `v1` leaves
  room for a deliberate breaking version later, never needed for compatible changes (C6).

### C3. `series_id`: SHA-256 of a length-prefixed canonical form, first 8 bytes in hex
Canonical form: source system, source instance, query id, then the tags **sorted by name**, each piece written
as `<length>:<text>`. Example: `7:datadog13:datadoghq.com16:3fa2…3:api12:/v1/invoices11:status_code3:500`.
- **Sorting** makes tag order irrelevant (spec: tags in a different order).
- **Length prefixes** make the form unambiguous: with a plain separator, a tag value containing `,` or `=`
  could make two different lines produce the same text.
- **SHA-256, first 64 bits:** stable in every language and Java version. With 100,000 lines the chance of any
  two colliding is about 1 in 3.7 billion (birthday bound n²/2⁶⁵).
**Alternative:** Java's `String.hashCode()`: only 32 bits (a collision is likely around 77,000 lines) and tied
to Java. A non-cryptographic hash (xxHash): faster, but needs a library; speed does not matter at one id per
new line.
`query_id` uses the same hash on the query text with whitespace collapsed and trimmed.

### C4. Events carry the full state, with an id and a sequence
Each `IncidentEvent` holds the whole `Incident`, not just what changed (**Event-Carried State Transfer**).
A viewer that joins late, or the Notifier after a restart, needs only the latest event per incident.
`event_id` lets a reader drop a repeated delivery (**Idempotency key**); `sequence` lets it ignore an older
event that arrives after a newer one (**latest wins**).
**Alternative:** small "delta" events (only what changed). Smaller, but a reader must replay every event in
order to know the current state.

### C5. Store questions over gRPC, answered in chunks
```proto
service StoreService {
  rpc Ping (PingRequest) returns (PingReply);
  rpc GetHistory (HistoryRequest) returns (stream HistoryChunk);          // one line, a time range
  rpc GetDaySummary (DaySummaryRequest) returns (stream SeriesDaySummary); // every line, one local day
}
message HistoryRequest { string series_id = 1; int64 from_ms = 2; int64 to_ms = 3; Resolution resolution = 4; }
enum Resolution { RESOLUTION_UNSPECIFIED = 0; RESOLUTION_RAW = 1; RESOLUTION_MINUTE = 2; }
message HistoryChunk { string series_id = 1; repeated Point points = 2; }   // at most 1000 points per chunk
message DaySummaryRequest { string date = 1; string time_zone = 2; }       // "2026-11-15", "Asia/Kolkata"
message SeriesDaySummary { string series_id = 1; int64 count = 2; double min = 3; double max = 4;
                           double mean = 5; double p95 = 6; }
```
**Why streaming:** 7 days of 10-second points is about 60,000 points; sending them in chunks keeps memory
small on both sides and lets the reader start before the end arrives.
**Alternative:** one big reply. Simpler, but memory grows with the time range.

### C6. Compatibility guard in the build (Tolerant Reader + a baseline check)
Readers ignore unknown fields (Protobuf does this by default: the **Tolerant Reader** pattern). Writers must
never change what an existing field number means. A test compares the current contracts with a committed
baseline (`contracts/src/test/resources/contracts-baseline.binpb`, a Protobuf descriptor set) and fails if:
a field number now has a different name, type or repeat-ness; a field disappeared without being `reserved`;
an enum value number changed name; a message or enum disappeared.
Adding fields, messages or enum values passes. After an accepted contract change, the baseline is refreshed
on purpose (`mvn -pl contracts test -Dcontracts.updateBaseline=true`).
**Alternative:** the `buf breaking` tool. Excellent, but another tool to install; the check needs about 100
lines of Java using Protobuf's own descriptors.

### C7. Checks a reader runs before using a message
`ContractChecks` returns a list of problems (empty means usable):
| Message | Checks |
|---|---|
| `MetricPoints` | `series_id` is 16 hex characters; at least one point; times strictly increasing; values are finite numbers |
| `SeriesDescriptor` | ids present; `series_id` equals the id recomputed from source, query id and tags; enums set; step > 0 |
| `IncidentEvent` | event id, incident id, type and incident present; `sequence` ≥ 1; the incident's id matches |

### C8. Kafka keys, and JSON for the browser
- Keys: `metric-series` and `metric-points` use `series_id`; `incident-events` uses `incident_id`. Kafka sends
  every message with the same key to the same partition, which keeps their order (spec: order per line).
- Topic names and keys live in one class (`Topics`), so no service types a topic name by hand.
- The browser gets JSON made from the Protobuf message with **the contract's own field names**
  (`series_id`, not `seriesId`), using Protobuf's JSON printer with "preserve field names" (`ContractJson`).
- RSocket routes (Notifier): `ping` and `incidents.live` (request-stream: the open incidents first, then every
  new `IncidentEvent`, as JSON). The old `incidents` route is removed.

### C9. Data structures, algorithms and patterns
| Problem | Choice | Cost |
|---|---|---|
| Don't resend unchanging text | **Flyweight**: descriptor once, points refer to it by id | Saves ~300 bytes per line per poll |
| Same line, any tag order → same id | **Sorting** the tags (a `TreeMap`) | O(k log k) for k tags |
| Unambiguous id text | **Length-prefixed encoding** | O(total length) |
| Stable, collision-safe id | **SHA-256**, first 64 bits | O(length); collision ≈ n²/2⁶⁵ |
| Points in time order? | **One pass**, comparing each point with the previous | O(n) |
| Viewer needs only the latest state | **Event-Carried State Transfer** | One event = full picture |
| Duplicate and out-of-order events | **Idempotency key** + **sequence, latest wins** (a hash map incident → highest sequence, in readers) | O(1) per event |
| New fields must not break old readers | **Tolerant Reader** + baseline **descriptor comparison** | O(number of fields) per build |
| Large history answers | **Server streaming in chunks** of ≤ 1000 points | Memory per chunk, not per range |
| Assembling messages | **Builder** (generated) | Immutable once built |

## Risks / Trade-offs

- [64-bit id collision] → negligible at this scale (above); `ContractChecks` recomputes the id from the
  descriptor, so a corrupted id is caught.
- [JSON for the browser drifts from the contract] → JSON is generated from the Protobuf message, never
  written by hand; a test compares field names.
- [Someone refreshes the baseline to silence the guard] → refreshing needs an explicit flag, and the baseline
  file is versioned, so the change shows in review.
- [Large incidents (hundreds of lines) make big events] → acceptable for now; if needed later, cap
  `series` per event and add a count field (a compatible change).
- [`p95` in a day summary is approximate when built from per-minute data] → stated in the Store's change.

## Migration Plan

The placeholder `ContractsCheck` message and the Notifier's `incidents` route are removed; nothing outside
this lab uses them. `Ping` stays.
