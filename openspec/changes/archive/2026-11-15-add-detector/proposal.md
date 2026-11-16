# Proposal: add-detector

## Why

This is the reason the whole project exists. Today Datadog monitors miss short spikes, fire too late, and nobody
can watch 10-15 dashboards by eye. The Collector already puts every 10-second value on Kafka within about
20 seconds, and the Store keeps them. Nothing yet **decides** what is unusual. The Detector watches every metric
line as its numbers arrive, spots a spike within seconds, and turns spikes that belong to the same problem into
**one incident**, so people get one clear alert per problem instead of fifty.

## What Changes

The Detector goes from an empty skeleton to a working service:

| Step | In plain words |
|---|---|
| **Read every number** | Reads `metric-points` and `metric-series` from Kafka (its own consumer group, separate from the Store) |
| **Know what is normal** | Per metric line, "normal" is the **median of its recent values** (default: the last 30 minutes). A median is not dragged up by the spike itself |
| **Watch every line** | Every line on the watched dashboards is judged by a rule: the app's APIs, the gateway, and the upstream services it calls. Nothing is left unwatched (user, 15 Nov: "I need to get notified if anything happens") |
| **Spot a spike** | Rules are matched per line (metric, tags, query, panel title, any case) from `config-repo/detector.yml`. Each says which direction is bad and how far: errors up 3x, traffic down to a third or up 3x, success rate 5 points down, error rate 5 points up, latency up 3x, CPU 20 points up, for 2-3 steps; tiny changes never count |
| **Notice silence** | A line that always has values and suddenly has none for a minute, while the rest of the dashboard still arrives, is an incident (an upstream that stopped answering) |
| **Group into incidents** | Spikes that overlap in time and belong to the same group (default: the same `api`, upstream service or service, otherwise the dashboard) become **one incident** listing every line, its peak, its normal value and its panel; the title says whether it is an upstream |
| **Close calmly** | An incident closes only after all its lines are back to normal for a while (default 2 minutes), so a flapping line does not open a new incident each time |
| **Save and announce** | Each incident is saved in the database (schema `incidents`) and announced on Kafka `incident-events` (opened / updated / closed), with neither able to happen without the other |
| **Start warm** | At start, it fills each line's last 30 minutes from the Store, so it can judge from the first value instead of waiting 30 minutes |
| **Use the data's own clock** | Every decision uses the time of the numbers, not the wall clock. So the 42-minute recording replayed in 15 seconds gives the same incidents as the live run |
| **Show its state** | Health: lines watched, lines still warming up, open incidents, how far behind Kafka it is |

## What is watched on the lab dashboards

| Area | Panels | Signals watched |
|---|---|---|
| Gateway (skeleton) | Request counts by status, success rate, latency avg/p95 | errors, traffic, success rate, latency |
| App APIs (skeleton, one graph per `api`) | Counts by status, success rate, latency avg/p95; requests per partner and per client | errors, traffic, success rate, latency |
| Upstream services (skeleton, one graph per `upstream_service`) | Counts by status, success rate, latency | errors, traffic, success rate, latency, silence |
| Swagstore web, backend, cart | Requests, success rate %, latency p50/p95, slowest calls, gRPC status codes, Redis commands | errors, traffic, success rate, latency |
| Swagstore calls between services | Calls, errors and p95 per caller and callee | errors, traffic, latency, silence |
| Simulated services | Requests by status, 5xx rate %, latency p95, CPU % | errors, traffic, error rate, latency, saturation |

## How we will know it works (no Datadog needed)

The 42-minute recording holds a known problem: every 8 minutes for 3 minutes, `/v1/invoices` latency jumps
6 times and its errors jump to 26%. Replayed, the Detector must:

| Check | Expected |
|---|---|
| Incidents for `/v1/invoices` | **6**, one per real problem (latency and errors in the same incident), not dozens |
| Time to open | Within 30 seconds of the spike's data being available |
| False alarms in calm periods | Counted and listed; the default rules are tuned until there are none or very few, and the result is recorded |
| Replay twice | The same incidents both times |
| Coverage | Every line of both dashboards is watched by a rule, shown per panel on the health page |

## Capabilities

### New Capabilities
- `anomaly-detection`: knowing what is normal per metric line, spotting spikes, grouping them into incidents,
  closing them after a calm period, and announcing them.

### Modified Capabilities
None at spec level. The incident message gains a few fields (each line's signal, reason and panel; the
incident's group and whether it is an upstream): additions only, which the compatibility guard allows. The 18 lines the Collector could not classify (case-sensitive rules) are still watched: the Detector's rules
match panel titles and queries in any case. The incident message (`IncidentEvent`) and the `incident-events` topic already exist from `add-contracts`.

## Lab limits (user, 15 Nov 2026)

- "Normal" is learned from at most the last **2 days**, because the Store keeps no more. The default is 30 minutes.
- Memory stays fixed: each line keeps only a small, fixed-size window of recent values (about 180 values for
  30 minutes), so 226 lines need about 1 MB.
- Incidents are small (a few rows per problem). Closed incidents older than 2 days are removed, like the numbers.

## Out of scope

| Not in this change | Where |
|---|---|
| The live web page and alerts in the browser | `add-notifier` |
| The daily Markdown report | `add-reporter` |
| Machine learning or seasonality ("Mondays are busier") | Not needed for spikes; may be revisited later |

## Impact

- `app/contracts`: `incident.proto` gains fields (design D8); baseline refreshed on purpose.
- `app/detector`: Kafka consumer, per-line detectors, incident grouping, database tables, outbox publisher, health, tests.
- Database `anomaly`: schema `incidents` gets its tables through versioned migrations (Flyway), like the Store.
- `config-repo/detector.yml`: rules per kind, grouping, calm period, warm-up. No code change to tune them.
- `scripts/system.sh`: the Detector receives the database password, like the Store.
- Testing uses the Collector's replay of `recordings/run-1`, so no Datadog is needed.
