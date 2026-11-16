# Design: add-notifier

## Context

See proposal.md for the why. Facts that shape the design:

- `incident-events` (Kafka, key = incident id, kept 2 days) carries `IncidentEvent`: the incident's full current
  state, a unique `event_id`, a `sequence` per incident (1, 2, 3...), and dashboard links (add-detector). Live
  traffic gave about 150 events in 35 minutes for 30 incidents: tiny.
- The Notifier skeleton serves RSocket over WebSocket on port 7104 with routes `ping` and `incidents.live`
  (returns nothing yet); its health page is on 8104. `config-repo/notifier.yml` has `rsocket-port` and
  `max-queued-updates-per-viewer`.
- The contract (add-contracts C8) says `incidents.live` sends the open incidents, then each new event, as one-line
  JSON with the contract's field names (`ContractJson`).
- The architecture left "the web page's framework" to this change.

## Goals / Non-Goals

**Goals:** every change on every page within 5 s; nothing missed across reconnects and restarts; one stalled page
never affects anyone; a page that is clear at a glance.
**Non-Goals:** storing anything, users and sign-in, acknowledging incidents, Slack or e-mail, history beyond today.

## Decisions

### N1. One reader, one board, many viewers
```
Kafka incident-events ──► reader thread ──► Board (one current state per incident)
                                               │ on every accepted change
                                               ▼
                                  Viewer 1 buffer   Viewer 2 buffer   ...   (latest state per incident)
                                       │ RSocket request-stream (backpressure: the page asks for N at a time)
                                       ▼
                                     page              page
```
- The reader **assigns all partitions and reads from the beginning** every start, without a consumer group and
  without committing: the board is rebuilt from what Kafka keeps (2 days), so a restart needs no database and does
  not ask the Detector (spec: knows the open incidents after a restart). About 1,000 events a day: a second to read.
- **Why not a consumer group:** a group remembers where it stopped, so after a restart the Notifier would not see
  incidents opened before it; rebuilding from the start is simpler and always right.

### N2. The board: one current state per incident
- A **hash map** `incident id -> latest event`. An event is accepted only if its `sequence` is higher than the
  one held (repeat or late older event: dropped). A repeat has the same `event_id` and the same `sequence`, so the
  sequence check also covers "drop by event id".
- **The whole day stays** (user, 16 Nov): the board keeps every incident whose **day** is today, open or closed.
  The day is the incident's `started_ms` as a date in the shared time zone (`anomaly.shared.time-zone`, default
  Asia/Kolkata: the same setting the Reporter uses, so the page and the report agree).
- Incidents are also filed in **day buckets** (a sorted map from day to incident ids). Once a minute (and at every
  settings change) the board checks the day: after midnight in the zone, every bucket before today is dropped,
  except incidents still open, which move to today's bucket until they close, then leave at the next check.
- Each accepted event is turned into JSON **once** (`ContractJson`) and that string is what every page receives.
- **Demo setting** `day-by: started | received` (default `started`): with `received`, the day is taken from when the
  Notifier received the incident's first event, so replaying the 15 November recording shows as today. Each accepted
  event is stamped with its received time for this.
- Checked with `ContractChecks` first; a broken event is counted, logged and never reaches a page.

### N3. Catch up without a gap
On connect, under the board's lock: register the viewer, then take a **snapshot** (every incident of the day,
open first, newest first). The page receives the snapshot, then one line
`{"snapshot_complete": true, "day": "2026-11-16", "time_zone": "Asia/Kolkata", "day_by": "started"}`, then changes. A change that happened during the snapshot may arrive twice; the page keeps the higher sequence, so
nothing is lost and nothing is doubled.

**Contract addition:** the `snapshot_complete` line is new. It lets the page tell "already open when I connected"
(no notification) from "new" (notify), and tells the page which day and time zone it is showing (all times on the
page are shown in that zone).
**Day or zone changes:** at midnight, or when the time zone setting changes, the Notifier ends every page's stream
with a normal completion; the pages reconnect at once and receive the new day's snapshot. One rule (reconnect gets a
fresh snapshot) covers restarts, midnight and zone changes alike. Additive: a reader that ignores lines it does not know is
unaffected; no reader exists yet. `docs/contracts.md` gains it.

### N4. Slow viewers: latest wins per incident
Each viewer has a **LinkedHashMap** `incident id -> newest event not yet sent`:
- a new change for an incident already waiting **replaces** it (counted as "merged"): a stalled page never builds a
  backlog, and when it reads again it gets each incident once, in its newest state;
- the map holds at most one entry per incident on the board, so memory per viewer is bounded by the board's size
  (a few hundred at most) instead of by time;
- sending happens only when the page has asked for more (RSocket `request(n)`): the reader thread only puts into
  the map and never waits, so one stalled page cannot delay the reader, other pages or anything upstream.
`max-queued-updates-per-viewer` (default 2000, about one busy lab day) becomes the cap of the map; above it the
oldest *closed* entries are dropped first: the page already has an older state of each, so it only misses an
intermediate update of a finished incident.

### N5. The page: how it looks (no framework, no build step)
Plain HTML, CSS and one script, served by the Notifier from its own resources (`/`, port 8104), so the page and the
Notifier always come together. **Why no framework:** a list that updates in place needs no React or build tooling;
nothing to install, nothing to keep up to date, and the page loads instantly.
```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ◉ Live incidents                      ● connected · last change 4 s ago     🔔 on   🔊 on    │  sticky header
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│  TODAY · Mon 16 Nov · Asia/Kolkata                                                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐                                      │  summary cards
│  │ 37 today │ │ 2 open ● │ │ 35 closed│ │ longest 4 min│                                      │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘                                      │
│  OPEN                                                                                        │
│  ┃ /v1/invoices: latency up, errors up                    since 17:04:00 · 2 min  ↗ dashboard│  red stripe: errors
│  ┃   latency up  1,569 ms   normal 138 ms   ▰▰▰▰▰▰▰▰▱▱ 11.3×   Latencies                     │  ratio meter
│  ┃   errors up       8      normal 0        ▰▰▰▰▰▰▰▰▰▰  new     Count                         │
│  ┃ upstream billing-core: latency up                       since 17:04:00 · 2 min  ↗ dashboard│  amber: latency
│  CLOSED TODAY                         [ / filter…        ]   billing 5 · /v1/invoices 5 · …   │  group chips
│  17:07  orders: errors up, latency up                       17:04:20 – 17:07:20 · 3 min   ▸  │  one line each
│  16:59  /v1/invoices: latency up, errors up                 16:56:00 – 16:59:50 · 4 min   ▸  │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```
| Element | Choice |
|---|---|
| Colours | Light and dark themes following the system setting; one colour per signal for the left stripe and the meter: errors and success/error rates red, latency amber, traffic blue, saturation violet, silent grey |
| How bad | A **ratio meter** per line: peak ÷ normal on a log scale (2×, 10×, 100×), with "×11.3"; percentages show the points instead ("−23 pts") |
| New incident | Slides in at the top with a short highlight (skipped when the system asks for reduced motion) |
| Numbers | Tabular digits so columns line up; thousands separators; the line's unit (ms, %, requests, s) |
| Times | In the day's time zone; open incidents show "since 17:04:00 · 2 min", closed ones "start – end · duration" |
| Empty day | "All quiet today ✓" |
| Keyboard | `/` jumps to the filter, `Esc` clears it |
| Small screens | One column; summary cards wrap |

### N5b. The page: data structures and algorithms (time and memory)
The page must stay instant on a busy day (spec: the page stays fast; target 2,000 incidents).

| Job | Data structure / algorithm | Time | Memory |
|---|---|---|---|
| Find an incident by id | **Map** id -> view (state, its row element, search text, group) | O(1) | one entry per incident |
| Keep open (newest start first) and closed (newest end first) in order | Two **sorted arrays of ids**; a change finds its place by **binary search**. New incidents are almost always the newest, so they go to the front | O(log n) to find, O(1) DOM move (`insertBefore`) | 2 arrays of ids |
| Many changes arrive together (snapshot, a burst) | **Coalesce** into a pending Map (highest sequence per id) and apply once per **animation frame** (`requestAnimationFrame`): at most one render per frame, work proportional to what changed | O(changes) per frame | pending map, cleared each frame |
| First paint of a whole day | Build all rows in a **DocumentFragment** and insert once: one layout instead of 2,000 | O(n) once | - |
| Closed incidents' details | **Lazy**: a closed row is one line; its lines are built only when expanded and dropped when collapsed | O(lines) on click | about 4 elements per closed row instead of about 20 |
| Off-screen rows | CSS `content-visibility: auto`: the browser skips layout and paint for rows not on screen | - | - |
| Summary cards and group chips | **Incremental counters**: open, closed, per-group counts in a Map, updated per change; "longest" is a running **max** (it can only grow during a day); chips re-sorted only when a count changed, at most once per frame | O(1) per change; O(g log g) for g groups (about 30) | one number per group |
| "2 min" ticking | **One** 1-second timer that updates only the open rows (a handful); closed durations never change | O(open) per second | one timer |
| Filter | Each incident keeps a lower-case **search text** (title + lines), built when it changes; typing is **debounced** (120 ms); matching toggles a CSS class instead of rebuilding rows | O(n x text) per search: about 1 ms for 2,000 | one string per incident |
| Clicks | **Event delegation**: one listener on the list for all rows | O(1) | 1 listener, not 2,000 |
| Dates and numbers | **Cached** `Intl.DateTimeFormat` (per time zone) and `Intl.NumberFormat`; making them is slow, using them is fast | O(1) per use | 2 objects |
| Several incidents open at once | **One** desktop notification for a burst ("3 new incidents: …") instead of three | O(1) | - |
| State kept | Only the **latest event per incident**, never a history of events | - | about 2 KB per incident: 4 MB for 2,000 |

**Why not virtual scrolling or an inverted index for the filter:** both pay off from tens of thousands of rows; at
2,000, `content-visibility`, lazy details and a 1 ms linear filter are simpler and use less memory. Noted for org
scale.

### N6. The RSocket client in the browser (user decision, 16 Nov: hand-written)
`rsocket-js` has no browser-ready file, so the page has its own small client, `static/rsocket.js` (about 200 lines),
for exactly what the page needs, following the RSocket 1.0 protocol over WebSocket (one frame per WebSocket
message, no length prefix):

| Frame | Direction | Use |
|---|---|---|
| SETUP | page -> Notifier | version 1.0, keepalive 20 s, lifetime 90 s, metadata `message/x.rsocket.composite-metadata.v0`, data `application/json` |
| REQUEST_STREAM | page -> Notifier | route `incidents.live` in composite metadata (well-known routing type `0x7E`), asks for 256 lines |
| REQUEST_N | page -> Notifier | asks for 128 more each time half of what it asked for has arrived (backpressure from the page) |
| PAYLOAD | Notifier -> page | one line of JSON (NEXT), or the end of the stream (COMPLETE) |
| KEEPALIVE | both | the page sends one every 20 s and answers the Notifier's |
| ERROR, CANCEL | both | an error ends the stream (the page reconnects); CANCEL when the page closes |

**Why not more:** request-response, fire-and-forget, channels, leases, resumption and fragmentation are not used by
the page (the Notifier sends each line whole). Kept out to stay small and testable.

### N7. Settings (config-repo/notifier.yml)
| Setting | Default | Meaning |
|---|---|---|
| `rsocket-port` | 7104 | WebSocket port for pages |
| `anomaly.shared.time-zone` | Asia/Kolkata | Shared with the Reporter: which "today", and the times shown. `UTC`, `America/New_York` etc.; validated, live |
| `day-by` | started | `started`: the day an incident started; `received`: the day the Notifier received it (demos of old recordings) |
| `max-queued-updates-per-viewer` | 2000 | Cap of each viewer's latest-wins map (about one busy lab day) |
| `spring.kafka.bootstrap-servers` | localhost:59092 | As the other services |

### N8. Health
Viewers connected, the day and time zone in use, today's incidents (open, closed), changes accepted, repeats and late events dropped, updates
merged for slow viewers, events set aside, seconds since the last change, whether the first read of the topic has
finished. DEGRADED while the first read is not finished (pages would see an incomplete list).

### N9. Data structures, algorithms and patterns
| Problem | Choice | Cost (n = incidents on the board, v = viewers) |
|---|---|---|
| One state per incident, repeats and late events | **Hash map** + **sequence check** (latest wins) | O(1) per event |
| Rebuild after a restart without a database | **Event sourcing light**: replay the topic from the start into the map | O(events kept), about a second |
| Hand each change to every page | **Observer / fan-out** to viewer buffers | O(v) per change |
| Do not turn the same event into JSON once per page | **Serialise once** when the event is accepted; every page gets the same string | O(1) per page instead of O(event size) |
| A stalled page must not build a backlog | **Coalescing buffer**: LinkedHashMap per viewer, newest replaces waiting | O(1) put, memory O(n) per viewer |
| Do not push faster than a page reads | **Backpressure** (RSocket request-n) | - |
| Catch up without a gap | **Snapshot, then changes**, registered under one lock; duplicates resolved by sequence | O(n log n) per connect (sort) |
| The day's incidents; yesterday's leave at midnight | **Day buckets**: a sorted map day -> incident ids; at midnight drop every bucket before today (closed ones), checked once a minute | O(1) per minute, O(removed) at midnight |
| Midnight or a zone change | **Re-snapshot**: end the streams; pages reconnect and catch up | O(n log n) per page, once |
| The page | See N5b: map + sorted arrays with binary search, frame batching, lazy rows, incremental counters, debounced filter | O(changes) per frame |
| Reconnect politely | **Exponential backoff** with a 10 s cap | - |

## Risks / Trade-offs

- [A hand-written protocol client may have mistakes] → only six frame types; tested against the real Spring RSocket
  server (task 1.1), including keepalive over several minutes.
- [The board is rebuilt from Kafka only] → events older than Kafka's 2 days are gone; an incident open for more
  than 2 days without any update would be missing after a restart. The Detector updates incidents at least when
  they close; an open-for-2-days incident is unrealistic in the lab. Noted.
- [A busy day makes each connect send the whole day] → about 1,000 incidents of about 2 KB: 2 MB once per connect
  on the same machine; fine for the lab. At org scale, send closed incidents in pages or as summaries.
- [An incident that starts at 23:59 and closes at 00:03] → it belongs to the day it started; it leaves at the first
  check after it closes (the next day's page shows it only while it is open).
- [Pages on other machines] → the page uses `ws://<same host>:7104`; only this machine for now (no sign-in).
- [Desktop notifications need permission and a focused page once] → a button asks; without it the page still works.

## Migration Plan

New code in `app/notifier` only, plus one documented JSON line on `incidents.live`. Removing: stop the Notifier.

## Open Questions

None that change the plan.
