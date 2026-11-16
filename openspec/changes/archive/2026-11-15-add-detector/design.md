# Design: add-detector

## Context

See proposal.md for the why. Facts that shape the design:

- Kafka `metric-points` (6 partitions, key = series id) carries about 775 messages and 1,200 finished points a
  minute for 226 lines; `metric-series` (compacted) carries each line's description, including `views` (dashboard
  id, dashboard title, panel title for every panel showing the line).
- The Collector polls every 15 s and fetches the last 10 minutes when it starts or meets a new panel (`backfill`).
  Values reach Kafka 20-50 s after their step ends (Datadog's ingestion lag), never partial in practice.
- The Store answers `GetHistory(series, from, to, RAW)` over gRPC on port 9102 and keeps 2 days.
- `IncidentEvent` (topic `incident-events`, key = incident id) already exists: full state in every event, a unique
  `event_id` and a `sequence` per incident, so readers can drop repeats (spec message-contracts).
- Schema `incidents` exists in the `anomaly` database and is empty. The Detector skeleton has settings and health.
- The architecture (archived design, DSA table) planned ring buffers, a two-heap sliding median with MAD, the State
  pattern, the Strategy pattern, union-find for grouping and a transactional outbox. This design keeps all of them
  but union-find (see D6).

## Goals / Non-Goals

**Goals:** judge every line with a rule from settings; open one incident per problem within seconds; never lose or
double an incident change; give the same result for the same data at any replay speed; fixed memory.
**Non-Goals:** the browser page (add-notifier), the daily report (add-reporter), seasonality or learning models,
running more than one Detector at a time.

## Decisions

### D1. One loop, all state in memory
```
Kafka (group "detector") ──► registry of line descriptions (metric-series)
                       └───► points (metric-points) ──► per line: rule ─► state machine ──► incident book
                                                                                        │ one DB transaction
                                                                     incidents + outbox ◄┘
                                                    outbox publisher ──► Kafka incident-events
```
- One consumer thread reads both topics and runs detection; one publisher thread empties the outbox. The lab rate
  (about 20 points a second) needs nothing more.
- **Why one instance:** grouping needs to see every line of a group together, and Kafka splits lines over
  partitions. At org scale, partition by group instead (noted for later).
- The consumer starts from the **latest** offset the first time (old points are not judged as news; the history
  comes from the Store, D3) and commits offsets after each batch is processed. In-memory state is rebuilt on restart
  (D3, D7), so the offsets only decide where to continue.

### D2. The data's own clock (event time)
Every time in the Detector is a value's timestamp, never `System.currentTimeMillis()`:
- **Steps in a row**, the **calm period** and the **history window** are counted in steps of the line's own values.
- **Silence** is measured against the **dashboard's watermark**: the newest step time seen on any line of that
  dashboard. A line is silent when the watermark is 1 minute past its last value.
- **Repeats:** each line remembers its last judged step time; a value at or before it is skipped (idempotent
  consumer). A recording replayed 3 times or a Kafka redelivery is judged once.
**Why:** a replay of 42 minutes in 15 seconds must give the same incidents (spec), and the Collector sends values
20-50 s late anyway; wall-clock rules would misfire on both.
The only wall-clock uses: removing old incidents (D9) and the "opened within 5 s" measurement.

### D3. What each line remembers
| Field | Structure | Size (30 min of 10 s steps, w = 180) |
|---|---|---|
| Recent values | **Ring buffer** of `double` | 180 values, about 1.4 KB |
| Normal (median) | **Two heaps** (max-heap of the lower half, min-heap of the upper half) with **lazy deletion** of values leaving the window | about 2 x 180 entries |
| Wobble (general rule, rules with `mad-factor`) | **MAD** (median of distances from the median, by **quickselect**; ignores up to half the values being unusual), or the mean distance when MAD is 0 (most values equal, e.g. a success rate at 100%) | computed once a minute |
| Present or missing per step | **Bit set** of 180 bits (3 `long`s); presence = `bitCount / 180` | 24 bytes |
| Last step time, state, steps in a row, peak | plain fields | - |
About 4-5 KB a line, about **1 MB for 226 lines**, fixed: nothing grows with time.

**Warm start:** the first time a line's value arrives (after a start or for a new line), the Detector asks the
Store for that line's raw values from 30 minutes before that value up to it, and fills the ring buffer, heaps and
bit set. Then it judges at once if the history covers at least 10 minutes (`min-history`). Warm-up uses the value's
own time, so a replay warms the same way. If the Store has nothing (a brand-new line), the Collector's 10-minute
backfill fills the history within the first poll.

### D4. Rules from settings (first match wins)
```yaml
anomaly:
  detector:
    history: 30m              # window for "normal" (at most 2d: the Store keeps no more)
    min-history: 10m          # judge a line only after this much history
    calm-period: 2m
    silence-after: 1m
    silence-min-presence: 0.9 # only lines with a value in at least 9 of 10 steps can go silent
    silence-skip-signals: [errors, error-rate]   # an error line that stops reporting is good news
    group-by: [api, upstream_service, peer.rpc.service, service, resource_name]   # first tag a line has; else its dashboard
    upstream-tags: [upstream_service, peer.rpc.service]
    incident-retention: 2d    # closed incidents kept (at most 7d)
    rules:
      - signal: errors
        match: { tag: { status_code: "4*|5*", http.status_code: "4*|5*", rpc.grpc.status_code: "!0|OK" }, metric: "*errors*" }
        direction: up
        factor: 3
        steps: 2
        min-change: 5
      - signal: success-rate
        match: { title: "*success rate*", query: "*1 - err*" }
        direction: down
        points: 5
        steps: 2
      - signal: error-rate
        match: { title: "*error*rate*" }
        direction: up
        points: 5
        steps: 2
      - signal: latency
        match: { metric: "*latency*|trace.*.request|trace.grpc.server|trace.grpc.client", title: "*latency*|*slowest*" }
        direction: up
        factor: 3
        steps: 2
        min-change: 50
      - signal: saturation
        match: { metric: "*cpu*|*memory*" }
        direction: up
        points: 20
        steps: 3
      - signal: traffic
        match: { metric: "*count*|*hits*|*requests*" }
        direction: both
        factor: 3
        steps: 2
        min-change: 5
    general:
      direction: both
      mad-factor: 6           # more than 6 x the usual wobble away from normal...
      at-least-factor: 2      # ...and at least twice or half normal
      steps: 3
```
- **Matching:** `*` is a wildcard, `|` separates alternatives, `!` negates; all comparisons ignore case. Within a
  rule, any listed field matching is enough. A line's metric, tags, query text and every panel title it appears on
  are checked.
- **Chain of Responsibility:** the rules form an ordered chain; the first that matches a line watches it, else the
  general rule. The chosen rule is **cached per line** and recomputed only when the settings version or the line's
  description changes, so matching costs nothing per value.
- **Order matters:** errors and the two rates come before traffic, because an error count is also a request count.
- **Validators** refuse a rule without a signal or direction, `history` above 2 days, `steps` below 1, or a
  `factor` of 1 or less; a refused change keeps the previous rules (runtime-configuration).

### D5. Judging a value (Strategy + State)
**Strategy** per measure, chosen by the rule:
| Measure | "Beyond" means (for direction up; mirrored for down) |
|---|---|
| `factor` | `value >= normal x factor` and `value - normal >= min-change` (down: `value <= normal / factor` and `normal - value >= min-change`) |
| `points` | `value - normal >= points` (percentage points) |
| `mad-factor` on a rule (rates, traffic) | additionally `abs(value - normal) >= mad-factor x MAD`: a success rate from 7 requests a step wobbles by 10 points, so it needs a 30-point fall (found in the replay, 15 Nov) |
| `mad` (general) | `abs(value - normal) >= mad-factor x MAD` and (`value >= 2 x normal` or `value <= normal / 2`) |
**State** per line (a small state machine):
```
NORMAL ──beyond──► SUSPECT(1..steps-1) ──beyond for `steps` in a row──► SPIKING ──not beyond──► CALMING
  ▲                    │ not beyond                                        ▲                     │ beyond again
  └────────────────────┘                                                   └─────────────────────┘
CALMING ──calm for `calm-period`──► NORMAL          any state ──silent (D2)──► SILENT ──value again──► CALMING
```
- **What enters the history:** values while SPIKING or CALMING are **not** added to the window (they would drag
  "normal" towards the spike). If a line stays SPIKING for longer than `history / 2` (15 minutes), its values are
  accepted again: a lasting change becomes the new normal, and the incident can close.
- The Detector never judges a partial value and never adds one to the history.

### D6. Incidents (the incident book)
- **Group key** = the first tag of `group-by` the line has (`api=/v1/invoices`), else `dashboard=<id>`. A tag value
  of `N/A` counts as missing. `peer.rpc.service` comes before `service`: on "Calls between services" a line is about
  the service being called (the upstream), so all callers of a broken service land in one incident.
- A **hash map** `group key -> open incident`: a line entering SPIKING or SILENT joins the open incident of its
  group, or opens one. O(1).
- An incident closes when every line in it has been NORMAL (or back from SILENT) for `calm-period`; its end time is
  the first calm step of the last line to calm down.
- **Reopen:** values are in data-time order per line, not across lines (Kafka partitions, different poll units).
  So a line can report a spike that started before its group's incident was closed by another line's later values.
  The last closed incident of each group is kept; a spike starting within `calm-period` of its end reopens it
  (UPDATED, state OPEN) instead of opening a second one (found in the replay, 15 Nov).
- **Title:** `[upstream ]<group value>: <signal> <up|down|silent>, ...` e.g. "upstream payments: success rate down,
  latency up". "upstream" when the deciding tag is in `upstream-tags`. Dashboard-grouped incidents use the
  dashboard title.
- **Events:** OPENED on open; UPDATED when a line joins or a line's peak grows by 10% or more (at most once per
  30 s of event time, so a growing spike does not flood readers); CLOSED on close.
**Why not union-find (planned in the architecture):** union-find joins things that are connected indirectly (A
with B, B with C). Here every line has a direct group key, so a hash map gives the same answer more simply. If
grouping by "spiked together, whatever the tags" is wanted later, union-find over overlapping intervals is the tool.

### D7. Saved and announced together (transactional outbox)
```sql
CREATE TABLE incidents.incident (
  incident_id text PRIMARY KEY, group_key text NOT NULL, state text NOT NULL, title text NOT NULL,
  started timestamptz NOT NULL, ended timestamptz, sequence bigint NOT NULL,
  body bytea NOT NULL,                 -- the full Incident (Protobuf)
  updated timestamptz NOT NULL);
CREATE UNIQUE INDEX one_open_per_group ON incidents.incident (group_key) WHERE state = 'OPEN';
CREATE TABLE incidents.outbox (
  id bigserial PRIMARY KEY, event_id uuid NOT NULL UNIQUE, incident_id text NOT NULL,
  payload bytea NOT NULL,              -- the IncidentEvent (Protobuf)
  created timestamptz NOT NULL, published timestamptz);
```
- Each incident change = **one database transaction**: upsert the incident row (sequence + 1) and insert its event
  into `outbox`. Both or neither.
- The **publisher** thread sends unpublished outbox rows in `id` order to `incident-events` (idempotent producer,
  `acks=all`), then marks them published. It is woken right after each commit, and also checks every second.
- **Crash between sending and marking:** the event is sent again after restart. Readers drop it by `event_id` and
  `sequence` (already in the contract), so a reader sees it once.
- The unique index makes "two open incidents for one group" impossible, even with a bug.
- **Restart:** open incidents are loaded from `incident.body`; each line's state is rebuilt from the Store (D3);
  lines in an open incident start in CALMING, so the incident continues and closes normally instead of opening a
  second one.

### D8. Contract additions (additive only)
The spec asks an incident to show each line's signal and panel, and whether it is an upstream:
```proto
message AffectedSeries { ...fields 1-7 unchanged...
  string signal = 8;          // errors, traffic, success-rate, error-rate, latency, saturation, general
  string reason = 9;          // "up", "down" or "silent"
  string dashboard_title = 10;
  string panel_title = 11; }
message Incident { ...fields 1-7 unchanged...
  string group_key = 8;       // e.g. "api=/v1/invoices"
  bool upstream = 9; }
```
Only additions, so `ContractCompatibilityTest` passes against the old baseline before it is refreshed on purpose;
`docs/contracts.md` gains the fields.

### D9. Keeping storage small
- Every hour: `DELETE FROM incidents.incident WHERE state = 'CLOSED' AND ended < now() - incident-retention`
  (default 2 days, at most 7) and published outbox rows older than 1 day. Plain tables: a few rows per incident.
- Memory is fixed per line (D3); lines no longer seen for `history` are dropped from memory.

### D10. Health
Details: lines watched per rule (and per dashboard and panel), lines warming up, lines on the general rule (by
panel, so a missing rule is easy to spot), lines grouped by dashboard, dashboards not arriving (watermark more than
1 minute behind the newest dashboard), open incidents (id, title, start), outbox rows waiting, Kafka lag, and the
time from a deciding value's arrival to its event being sent (p50/p95, ring buffer of the last 1000).
DEGRADED when the outbox has rows older than 30 s (announcing is stuck) or a dashboard is not arriving.

### D11. Data structures, algorithms and patterns
| Problem | Choice | Cost (w = 180 values in the window, L = lines, R = rules) |
|---|---|---|
| Recent values in fixed memory | **Ring buffer** per line | O(1) add, O(w) memory |
| Normal that one spike cannot move | **Sliding median with two heaps** and lazy deletion | O(log w) per value |
| Usual wobble for the general rule and rules with `mad-factor` | **MAD** by **quickselect** | O(w) average, once a minute |
| Did the line usually have values | **Bit set** + popcount | O(1) add, O(w/64) to count |
| Which rule watches a line | **Chain of Responsibility**, result **cached** per line | O(R) once per line and settings version, O(1) per value |
| Different measures (factor, points, wobble) | **Strategy** | O(1) |
| Normal, suspect, spiking, calming, silent | **State** pattern per line | O(1) per value |
| Join an incident | **Hash map** by group key | O(1) |
| Silent lines | Dashboard **watermark**; scan the dashboard's lines when it moves to a new step | O(lines of the dashboard) per step |
| Same value twice | **Idempotent consumer**: last judged step per line | O(1) |
| Save and announce without losing either | **Transactional outbox** | One extra row per change |
| Never two open incidents per group | **Partial unique index** | O(log n) per write |
| Replay gives the same result | **Event time** everywhere | - |
| Time-to-announce percentiles | **Ring buffer** of the last 1000 | O(1) add, O(k log k) on read |

## Risks / Trade-offs

- [Lines of one dashboard come from different poll units, up to about 15 s apart] → silence needs 1 minute of
  watermark lead, 4 times that gap; the replay counts false silences, and `silence-after` is a setting.
- [Default rules may be too sensitive or too dull for some panels] → the replay counts incidents outside the known
  problem; the defaults are tuned until there are none or very few, and the result is recorded.
- [Datadog sends nothing for a zero count] → such lines have low presence, so they never alarm on silence, and a
  drop to zero in traffic shows as silence only on lines that always have values.
- [A lasting change keeps alarming] → after 15 minutes of SPIKING the new level becomes normal (D5).
- [A replay at full speed reads Kafka partitions unevenly, by minutes] → each line's decisions do not depend on it
  (two replays gave the same 655 line events), but grouping does: a line read minutes late can join a later
  incident of its group. Live, lines of one answer arrive within seconds, well inside the calm period. A paced
  replay would need a Collector setting (noted for later). Found 15 Nov.
- [Datadog revises its newest steps] → the Collector re-sends corrected values; the Detector judges the first value
  of each step and skips later corrections (8% of replay messages carry such corrections). Judging corrections
  would need a short wait per step; not needed for spikes lasting minutes.
- [Single instance] → enough for 15 dashboards; a second instance would need partitioning by group.
- [Warm start makes one Store call per line] → 226 calls of about 1 ms; for thousands of lines, a batch question
  would be added to the Store.

## Migration Plan

New tables only (schema `incidents` is empty), created by Flyway like the Store's. The contract gains fields only.
`config-repo/detector.yml` is rewritten (the skeleton's three settings become `rules`). Removing: stop the
Detector; `DROP SCHEMA incidents CASCADE` deletes incidents (only on purpose).

## Open Questions

None that change the plan. The default numbers are a starting point that the replay check (tasks) will confirm.
