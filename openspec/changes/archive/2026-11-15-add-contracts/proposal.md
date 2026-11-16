# Proposal: add-contracts

## Why

The 6 services can start and talk, but they have not agreed on **what** they say to each other. Before any
service does real work, the exact messages must be fixed in one shared place, so each service can be built
and tested on its own, and a change in one service cannot silently break another.

## What Changes

The exact shape of every message, written once in the shared `contracts` module (Protobuf files):

| Message | From → To | Travels on | In plain words |
|---|---|---|---|
| `SeriesDescriptor` | Collector → Store, Detector | Kafka `metric-series` | What one metric line is (name, tags, dashboard, kind, unit). Sent rarely |
| `MetricPoints` | Collector → Store, Detector | Kafka `metric-points` | The newest numbers of one line. Sent on every poll |
| `IncidentEvent` | Detector → Notifier | Kafka `incident-events` | An incident opened, changed or closed, with its full current state |
| `StoreService` (gRPC) | Detector, Reporter → Store | gRPC | Questions: "history of this line", "summary of this day" |
| Live routes (RSocket) | Notifier → web page | RSocket over WebSocket | "Open incidents, then live updates" |

Also:
- **A stable id for every metric line** (`series_id`): the same line always gets the same id, whatever order
  its tags arrive in.
- **Rules that keep old and new services compatible** when a message gains a field, checked automatically by
  the build.
- **A shared checker** that tells a service whether a received message is complete before it uses it.
- The placeholder messages from the skeleton (`ContractsCheck`, the empty `incidents` stream) are replaced.

## Capabilities

### New Capabilities
- `message-contracts`: the messages services exchange, how each metric line is identified, and the rules
  that keep messages compatible as they change.

### Modified Capabilities
None. `runtime-configuration` is not affected.

## Out of scope

| Not in this change | Why |
|---|---|
| Services actually sending or reading these messages | Each service's own change (`add-collector` first) |
| Database tables | `add-store` |
| How spikes are detected | `add-detector` |
| The web page itself | `add-notifier` |
| Alert routing to people (email, chat) | Not planned yet |

## Impact

- `app/contracts`: new `.proto` files, a small Java helper for series ids and message checks, and tests.
- `app/store`: its gRPC service gains the real method names (still answering with no data until `add-store`).
- `app/notifier`: the RSocket route names change to the agreed ones (still empty until `add-notifier`).
- No change to Kafka topics, settings, or Datadog.
