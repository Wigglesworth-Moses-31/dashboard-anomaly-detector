# Proposal: add-system-architecture

## Why

At work there are too many Datadog dashboards for people to watch, and Datadog monitors miss short spikes
and fire late. This change sets up the whole system's shape (its parts, how they talk, and the rules every
part follows) so each part can then be specified and built one at a time.

## What Changes

- A new system made of **6 small services**, each with one job:

  | Service | Job, in plain words |
  |---|---|
  | Config Service | Hands every service its settings, read from files outside the code |
  | Collector | Reads the chosen dashboards and pulls their latest numbers from Datadog |
  | Store | Saves the numbers so they can be looked up later |
  | Detector | Watches the numbers as they arrive and spots spikes |
  | Notifier | Shows alerts live on a web page |
  | Reporter | Writes a daily Markdown report, then clears old raw numbers |

- **How they talk:** Kafka carries the stream of numbers and alerts; gRPC is used when one service asks
  another a question; RSocket streams live alerts to the web page.
- **Where data lives:** one TimescaleDB database (Postgres with time-series support) holds both the numbers
  and the incidents.
- **Settings:** every setting lives in YAML files in a folder kept in Git. Changing a setting never needs a
  code change or a rebuild.
- **A project skeleton:** an empty, runnable version of all 6 services plus Kafka and the database on this Mac,
  so later changes fill in real behaviour.

## Capabilities

### New Capabilities

- `runtime-configuration`: all settings live outside the code, are applied without a restart, and bad
  settings are rejected safely. This change delivers it fully, because the skeleton already includes the
  Config Service and every service reading from it.

### Modified Capabilities

None. This is the first change in the project.

### Planned capabilities (specified in later changes, not this one)

A spec describes what the system does once its change is finished. This change only builds the skeleton,
so the behaviour of each service is specified in that service's own change. Drafts are kept in
`docs/spec-drafts/` and will be the starting point:

| Capability | Draft | Will be specified in |
|---|---|---|
| `metric-collection` | `docs/spec-drafts/metric-collection.md` | `add-collector` |
| `anomaly-detection` | `docs/spec-drafts/anomaly-detection.md` | `add-detector` |
| `live-alerting` | `docs/spec-drafts/live-alerting.md` | `add-notifier` |
| `daily-report` | `docs/spec-drafts/daily-report.md` | `add-reporter` |

## Out of scope

| Not in this change | Why |
|---|---|
| Pulling APM traces | Datadog keeps only sampled traces (the lab test showed the API returns only kept traces) |
| Grafana or Prometheus as a source | Not needed now; the Collector reads data through one interface so another source can be added later |
| Other Datadog accounts | Built and tested on a personal trial account only |
| Real behaviour of each service | Each service gets its own change later (`add-contracts`, then one per service) |
| The web page's look and framework | Decided in the Notifier's change |

## Impact

- New code: a Maven multi-module project with 7 modules (6 services plus one shared `contracts` module for
  the Protobuf message definitions).
- New local tools in Docker (via Colima): Kafka and TimescaleDB, on ports chosen not to clash with the
  user's other containers.
- New `config-repo/` folder with the YAML settings.
- New dependencies: Java 25, Spring Boot 4.0.9, Spring Cloud Config and Bus (2025.1.4), Spring gRPC 1.0.3,
  RSocket, Apache Kafka client, Protobuf.
- Datadog: read-only use of the personal trial's API (dashboards and metric queries). Nothing is created or
  changed in Datadog by this change.
