# Design: add-system-architecture

## Context

See proposal.md for the why. Facts that shape the design:

- The lab already has Datadog data: Swagstore plus a traffic generator feed a personal Datadog trial
  (site `datadoghq.com`), which ends around 26 November 2026.
- Datadog limits API use. The metrics query API is rate-limited per organisation, and the span API allows
  50 calls a minute (measured 15 Nov 2026).
- This Mac has Maven 3.9.11 and Java 17, 18 and 21. Docker runs in Colima (4 CPUs, 8 GB), which also runs
  the user's other containers (an `ai-engineering-bootcamp` stack and a `cka` kind cluster on port 8080).
- The user is learning these technologies, so each choice must be explainable in a sentence.

## Goals / Non-Goals

**Goals:**
- Fix the shape of the system: the 6 services, what each owns, and how they talk.
- Choose the tools, each with a reason and the alternative that was rejected.
- Produce a runnable skeleton: all 6 services start, read their settings from the Config Service, and
  report healthy; Kafka and the database run locally.

**Non-Goals:**
- Real behaviour inside any service (later changes, one per service).
- The exact message fields (the `add-contracts` change).
- Running anywhere other than this Mac.

## Decisions

### D1. Six services, each with one job
```
                         Config Service  (settings from config-repo/, over HTTP)
                               │ every service reads its settings here
                               ▼
Datadog ─► Collector ─► Kafka: metric-series, metric-points ─┬─► Store ─► TimescaleDB
                                                              │      ▲ gRPC (history)
                                                              └─► Detector ─► incidents ─┐
                                                                     │                    │
                                                          Kafka: incident-events          │
                                                                     ▼                    ▼
                                                                  Notifier            Reporter ─► reports/2026-11-15.md
                                                                     │ RSocket (WebSocket)
                                                                     ▼
                                                                  Web page
```
| Service | Owns | Reads | Writes |
|---|---|---|---|
| Config Service | The settings | `config-repo/` (Git) | Settings to services |
| Collector | Talking to Datadog | Dashboard definitions, metric queries | `metric-series`, `metric-points` |
| Store | The numbers | `metric-points`, `metric-series` | TimescaleDB; answers gRPC questions |
| Detector | Deciding what is unusual | `metric-points`, `metric-series`; Store (gRPC) at start | Incidents table, `incident-events` |
| Notifier | Live view | `incident-events`; open incidents at start | RSocket stream to browsers |
| Reporter | The daily summary and cleanup | Incidents table, Store (gRPC) | `reports/*.md`; deletes old raw data |

**Why:** each service can be built, tested and explained on its own, and a slow part (for example the
Reporter at night) cannot slow the alerting path.
**Alternative:** one application with six modules. Simpler to run, and honestly enough for 10-15
dashboards. Rejected because learning microservice communication is part of the project's goal.

### D2. Kafka for streams of data
| Topic | Key | Holds | Kept |
|---|---|---|---|
| `metric-series` | series id | What each metric line is (name, tags, dashboard, kind, unit) | Compacted: the latest per line |
| `metric-points` | series id | The numbers from each poll | 2 days |
| `incident-events` | incident id | Incident opened / updated / closed | 2 days |

Store and Detector read `metric-points` as two separate consumer groups, so each gets every message and
neither waits for the other. Using the series id as the key keeps each line's points in order.
**Why:** Kafka keeps messages after they are read, lets several services read the same stream, and keeps
order per key.
**Alternative:** RabbitMQ. Simpler to start, but messages are usually gone once read, and "two services
read everything" needs extra setup.

### D3. gRPC when one service asks another a question
The Store answers questions such as "give me the last 7 days of this line, per minute" (Detector at start,
Reporter at night).
**Why:** typed request and response from the same Protobuf files as the messages, fast, and it can stream
long answers.
**Alternative:** REST with JSON. Easier to try by hand, but untyped, and a second message format next to
Protobuf.
**Found in task 4.3:** Spring gRPC starts its server only when at least one gRPC service exists, so the
skeleton has a placeholder `StoreService` with one `Ping` call (in `contracts`), and the build also
generates gRPC classes (`protoc-gen-grpc-java` 1.77.1). The standard gRPC health service answers too.

### D4. RSocket to stream alerts to the web page
The Notifier serves RSocket over WebSocket. A page asks for "open incidents, then updates" (request-stream).
**Why:** RSocket has backpressure built in: a slow page tells the server how much it can take, which is
exactly the "slow viewers never slow the system" requirement.
**Alternative:** Server-Sent Events. Simpler and supported everywhere, but one-way and without
backpressure. Kept as the fallback (see Risks).

### D5. Spring Cloud Config Server for settings
Settings are YAML files in `config-repo/`, a folder tracked in Git. The Config Service (Spring Cloud Config
Server) serves them. `config-repo/` is its own Git repository; a post-commit hook
(`platform/install-config-hook.sh`) asks the Config Service to broadcast a refresh over Kafka (Spring
Cloud Bus, topic `springCloudBus`), and every service reloads. Measured: 1.8 s from commit to new value. Each service validates new settings and keeps the last valid ones if they are wrong.
**Why:** a standard, well-documented tool that does exactly this, so no custom code to maintain.
**Change from the chat:** earlier the plan said services would fetch settings over gRPC. Spring Cloud
Config uses HTTP, and writing a custom gRPC config server would add code without adding value. gRPC is still
used where it fits (D3). Approved by the user on 15 Nov 2026.
**Alternative:** a hand-written config service over gRPC; or plain files on each machine (no central place).

### D6. One TimescaleDB database for numbers and incidents
TimescaleDB is Postgres with time-series features. Two schemas: `metrics` (points, in a hypertable split
into one chunk per day) and `incidents` (ordinary tables).
**Why:** one engine instead of two; plain SQL; daily chunks make "delete yesterday" a near-instant
`drop_chunks` instead of deleting rows one by one; continuous aggregates give the per-minute summaries the
report and the Detector's baselines need.
**Alternative:** a dedicated time-series database (InfluxDB, VictoriaMetrics, QuestDB) plus a separate
Postgres for incidents. Faster at very large scale, but two databases to learn and run.

### D7. Protobuf for every message and gRPC call
One shared `contracts` module holds the `.proto` files; the build generates Java classes for every service.
**Why:** one contract, typed, small; field-number rules make adding fields safe.
**Alternative:** JSON. Easier to read, but untyped and larger.

### D8. Java 25 (LTS) instead of Java 26 (approved by the user on 15 Nov 2026)
Virtual threads, the main reason Java 26 was named, exist in both. Java 25 is a long-term-support release;
Java 26 is not, and its free updates from Oracle ended in September 2026.
**Why:** an org reusing this code will want the long-term-support version.
**Alternative:** Java 26, as originally named. Works with Spring Boot 4.1; only the support window differs.
Neither is installed yet; the first task installs the chosen one.

### D9. Spring Boot 4.0.9 with Spring Cloud 2025.1.4 and Spring gRPC 1.0.3, Maven multi-module
**Changed during task 1.2 (15 Nov 2026):** the plan said Spring Boot 4.1. Spring Cloud's release for Boot 4.1
(2026.0.0) is still a release candidate, while Spring Cloud 2025.1.4 (Config Server, Bus) is final and made
for Boot 4.0.x. So the project uses the newest fully released set: Spring Boot 4.0.9, Spring Cloud 2025.1.4,
Spring gRPC 1.0.3. All support Java 25. Upgrade to Boot 4.1 when Spring Cloud 2026.0 is final.
Maven 3.9.11 is already installed. The project lives in `app/`, with modules `contracts`, `service-kit`,
`config-service`, `collector`, `store`, `detector`, `notifier`, `reporter`. `service-kit` (added in task
3.2) holds what every service shares: the settings snapshot and validator chain (D13), the
`/actuator/settings` page and the settings health check, so the six services do not copy that code. A build rule (Maven Enforcer)
refuses any Java other than 25, with a message pointing to `source ../env.sh`.
**Alternative:** Spring Boot 4.1 with the Spring Cloud release candidate (not final, so unsafe to build on);
Gradle instead of Maven (equally good; Maven is already here and more common in Java courses).

### D10. The data source sits behind one interface
The Collector talks to Datadog through one interface (find dashboards, fetch points, report rate limits).
Only the Datadog implementation is written now.
**Why:** costs nothing today and lets a Prometheus/Grafana source be added later without touching other
services.

### D11. Delivery: at least once, stored once
Kafka may deliver a message twice after a failure. The Store writes with "insert or update" on
(series id, step time), and the Detector ignores points it has already seen. So duplicates are harmless.

### D12. Local ports chosen to avoid clashes
| What | Port |
|---|---|
| Config Service | 8888 |
| Collector, Store, Detector, Notifier, Reporter (health) | 8101 to 8105 |
| Store gRPC | 9102 |
| Notifier RSocket (WebSocket) | 7104 |
| Kafka | 59092 |
| TimescaleDB | 55432 |
The `cka` cluster uses 8080 and 8443, and the bootcamp stack runs its own Postgres, so default ports
(8080, 5432, 9092) are avoided.

### D13. Data structures, algorithms and patterns: where each one earns its place
Rule for the whole project: a data structure or pattern is used only where it solves a real problem in
that service, and its cost (Big-O) is written next to it. Each service's own change details its part.

**Used in this change (the skeleton):**
| Problem | Choice | Cost | Why it fits |
|---|---|---|---|
| Datadog must be swappable later | **Ports and Adapters** (a `MetricSource` interface, one Datadog adapter) | - | Only the adapter knows the vendor (D10) |
| Services must react when settings change | **Observer / publish-subscribe** (refresh event over Kafka) | - | Nobody polls for changes; the change is pushed |
| A reload must never expose half-updated settings | **Immutable settings snapshot, swapped atomically** (`AtomicReference`) | O(1) read, O(1) swap | Readers always see a complete, valid set; "keep the last good one" falls out for free |
| Settings must be checked before use | **Chain of Responsibility** (one validator per rule, run in order) | O(number of rules) | New checks are added without touching existing ones |
| Messages are built in many places | **Builder** (generated by Protobuf) | - | Messages are immutable once built |

**Planned for later changes (detailed in each service's design):**
| Service | Problem | Choice | Cost |
|---|---|---|---|
| Collector | Run thousands of polls on time | **Min-heap** ordered by next-due time | O(log n) per schedule |
| Collector | Stay under Datadog's rate limit | **Token bucket** | O(1) per request |
| Collector | Many slow network calls at once | **Virtual threads** with structured concurrency | Cheap threads; waiting costs no OS thread |
| Collector | Find every query in nested dashboard JSON | **Depth-first tree walk** | O(number of widgets) |
| Collector | A stable id for each metric line | **Hash** of name + **sorted** tags | O(k log k) for k tags |
| Collector | Skip points already sent | **Hash map** series → last timestamp | O(1) |
| Collector | Datadog briefly failing | **Retry with exponential backoff and jitter**, **circuit breaker** | - |
| Store | Fast writes | **Batching** (buffer, then one bulk write) | Fewer round trips |
| Store | Delete a whole day fast | **Time partitioning** (one chunk per day) | Drop a chunk instead of row-by-row deletes |
| Detector | Recent history in fixed memory | **Ring buffer** per series | O(1) add, fixed size |
| Detector | Running mean and spread | **Welford's algorithm**, **EWMA** | O(1) per point |
| Detector | A baseline one spike cannot distort | **Sliding-window median with two heaps**, plus MAD | O(log w) per point |
| Detector | Normal → suspect → spike → recovering | **State pattern** (a small state machine per series) | O(1) |
| Detector | Different rules for counts, latency, ratios | **Strategy pattern** (one detector per kind) | - |
| Detector | Group spikes into one incident | **Union-find (disjoint sets)**, **merge intervals** | Near O(1) per union |
| Detector | Save incident and publish event without losing either | **Transactional outbox** | - |
| Detector | Kafka may deliver twice | **Idempotent consumer** (last seen timestamp per series) | O(1) |
| Notifier | One stalled browser must not block others | **Bounded queues + "latest wins" map** per viewer | O(1) per update |
| Reporter | Daily p95 without keeping every value | **t-digest** (mergeable sketch) | Small fixed memory |
| Reporter | Worst N endpoints | **Min-heap of size N** | O(n log N) |
| Reporter | Run exactly once per day | **State machine** (pending → written → verified → cleaned) with an idempotency key per date | - |
| Reporter | Report sections in a fixed order | **Template Method** | - |

## Risks / Trade-offs

- [Datadog rate limits] → batch several queries per request; a token bucket that follows the limit and
  reset headers Datadog returns; poll interval set in configuration.
- [Datadog trial ends ~26 Nov] → build and test the Collector first; save real Datadog responses to files
  so later services can be tested by replaying them, with no live account needed.
- [Memory on the Mac: Colima has 8 GB for Swagstore, the traffic generator, the user's other containers,
  Kafka and TimescaleDB] → run the 6 services as normal Java processes on the Mac, not in Docker, during
  development; stop Swagstore when not testing against live data.
- [RSocket's ecosystem is less active than gRPC's, especially the browser library] → keep the Notifier's
  output behind one interface; Server-Sent Events is the fallback if RSocket in the browser is troublesome.
- [Six services is a lot to run alone] → one parent build, one shared `contracts` module, and one script to
  start or stop everything.
- [At-least-once delivery means duplicates] → handled by D11.

## Migration Plan

New system; nothing to migrate. To remove it: stop the services, then `docker compose down` for the
platform file. Datadog is never changed, so there is nothing to undo there.

## Open Questions

Safe to answer later, in the change for the service named:
- The web page's framework (Notifier change).
- Exact detection rules and their default numbers (Detector change).
- Retention defaults for summaries and incident evidence (Reporter change).
