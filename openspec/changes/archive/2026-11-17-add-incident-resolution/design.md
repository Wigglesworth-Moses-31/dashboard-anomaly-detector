# Design: add-incident-resolution

## Context

See proposal.md. Facts from the code:

- **Detector (`IncidentBook`)**: open incidents by group key (`byGroup`), their lines (`bySeries`). When all lines are
  calm for `calm-period`, the incident is removed from `byGroup`, put in `recentlyClosed` (to reopen a late spike
  within one calm period) and a CLOSED change is made. Changes are saved with the incident and an outbox row in one
  transaction, then published (add-detector D7). On start, incidents with `state = 'OPEN'` are restored.
- **Detector engine**: one consumer thread reads `metric-series` and `metric-points`; the book is touched only by it.
- **Housekeeping** deletes only `state = 'CLOSED'` incidents past the retention.
- **Notifier (`Board`)**: an incident is on the page while its state is not CLOSED (any day); closed ones leave at the
  day's end. Notifications are sent for OPENED events after the snapshot.
- **History** (`LineHistory.reasonOf`) already has the slot for a reason.

## Decisions

### S1. Contract

```proto
enum IncidentState { … INCIDENT_STATE_CALM = 3; }        // all lines normal again; waiting for a reason
message Incident  { … string reason = 10; int64 resolved_ms = 11; }
message ResolveIncident {                                 // topic incident-commands, key = incident id
  string command_id = 1; string incident_id = 2; string reason = 3; int64 requested_ms = 4;
}
```

| Transition | Event type | State |
|---|---|---|
| A spike opens a group | OPENED | OPEN |
| All lines calm for `calm-period` | UPDATED | **CALM** (`ended_ms` = first calm step) |
| Spike while calm | **OPENED** again (same incident id, next sequence) | OPEN (`ended_ms` cleared): pages notify, it is the problem back |
| A reason arrives | CLOSED | CLOSED (`reason`, `resolved_ms`) |

New enum value, fields and message only: the compatibility check passes; the baseline is refreshed on purpose.
`ContractChecks` gains the command (id, incident id, reason 1–500 characters).

### S2. The Detector: calm instead of closed

| Where | Change | Cost |
|---|---|---|
| Calm | The incident moves from `byGroup` to `calm: Map<groupKey, Open>`; its lines leave `bySeries`; state CALM | O(lines) |
| Spike in a group | `byGroup` → else **`calm`** (no time limit) → else `recentlyClosed` within one calm period (late lines after a reason) → else new | O(1) |
| Restore at start | Load `state IN ('OPEN','CALM')`; calm ones into `calm` | — |
| Clean-up | Unchanged: only CLOSED are deleted | — |

### S3. Closing by a reason: a command, one writer

```
page ─POST /incidents/{id}/reason─► Notifier ─ResolveIncident (command id)─► Kafka incident-commands
                                                                                │
Detector engine (the same single thread that owns the book) ◄───────────────────┘
   book.resolve(id, reason, now): open or calm → CLOSED (reason, resolved_ms); unknown / closed / bad reason → nothing
   saved + outbox in one transaction → incident-events CLOSED ──► Notifier ──► page
```

| Concern | Choice | Why |
|---|---|---|
| Who changes incidents | Only the Detector (single writer) | No two services can disagree about an incident |
| How the request reaches it | A Kafka command topic, read by the Detector's existing consumer | The book stays single-threaded (no locks); durable: a command sent while the Detector restarts is applied when it is back |
| Repeats | The second command finds the incident closed and does nothing | Idempotent by state; the command id is logged for tracing |
| Offsets | Committed after the change is saved, like metric points | A crash between apply and save re-applies the command once (still idempotent) |
| Answer to the page | The CLOSED event itself (no reply topic) | The page already listens to incident events |

The Notifier gets a small Kafka producer for commands; `POST` validates the reason (1–500 characters) and answers
`202` with the command id.

### S4. Never lost while waiting

Every hour the Detector's engine checks its open and calm incidents; any not announced for 12 hours is announced again
(UPDATED, next sequence, same content). Kafka keeps 2 days, so a rebuilding Notifier always finds them. O(waiting
incidents) per hour.

### S5. The page

| Piece | How |
|---|---|
| "Needs a reason (n)" | A section above the closed list: calm incidents (state CALM), newest calm first; the same rows and trees as open ones, with "calm since …" in the display time zones |
| Reason box | In each open and calm row: a text field (500 characters max, counter) and "Close with reason"; Enter sends |
| Feedback | `pending: Map<incidentId, {text, deadline}>`; "saving…" until that incident's CLOSED event arrives (then removed); after 10 s "not saved", text kept; O(1) per event |
| Closed rows | Show the reason and when it was given (display zones) |
| History | `LineHistory` reads `incident.reason` |

### S6. Data structures, algorithms and patterns

| Need | Choice | Cost |
|---|---|---|
| Waiting incidents | Hash map group key → calm incident | O(1) per spike |
| Commands | Kafka topic, single consumer thread (Command pattern, single writer) | O(1) per command |
| Repeats | Idempotent by state | — |
| Re-announce | Hourly scan of waiting incidents | O(waiting) |
| Page feedback | Hash map of pending reasons with deadlines | O(1) per event |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Many incidents waiting for reasons | Shown with a count; the list is the to-do; nothing is deleted |
| The Detector is down when a reason is sent | The command waits in Kafka and is applied on start; the page says "not saved" after 10 s, and the incident closes when the Detector is back |
| Old calm-like incidents (closed before this change) | Stay closed, reason "—" |

## Migration Plan

Deploy contracts, Detector, Notifier together; create the topic (`create-topics.sh`). Incidents open at the switch
keep running; the first calm one becomes CALM.

## Open Questions

None.
