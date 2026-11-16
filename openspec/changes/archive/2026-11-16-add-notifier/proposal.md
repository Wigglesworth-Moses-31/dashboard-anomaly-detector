# Proposal: add-notifier

## Why

The Detector now opens one incident per problem within a second of the data arriving, but the incidents only sit
in a database and on a Kafka topic: nobody sees them. The user asked to "get notified if anything happens" and
chose a live page "like Cricinfo": open it once, and problems appear, change and disappear by themselves, each with
a link to the right Datadog dashboard at the right time. This is also the part of the project people can see in a
two-minute demo.

## What Changes

The Notifier goes from an empty skeleton to a working service:

| Step | In plain words |
|---|---|
| **Read every incident change** | Reads `incident-events` from Kafka. At start it reads the topic from the beginning (2 days, small), so it knows every open incident without asking the Detector or its database |
| **Keep the current state** | One entry per incident, the newest by `sequence` wins; repeats (same `event_id`) are dropped. **The whole day stays** (user, 16 Nov): every incident that started today, open or closed, in the shared time zone (Asia/Kolkata; switchable to UTC or New York without a restart). Yesterday's closed incidents leave at midnight |
| **Stream to browsers (RSocket over WebSocket)** | A page asks once: it gets every incident of the day first, then each change as it happens. Port 7104, route `incidents.live` (already in the contract) |
| **Slow viewers never slow anyone** | Each viewer has a small "latest state per incident" buffer. A stalled browser tab gets the newest state of each incident when it wakes up, never a backlog, and never delays other viewers or the Detector |
| **The page** | Served by the Notifier at `http://localhost:8104`: a summary of the day, open incidents on top in full (title, how long, each line's signal, peak against normal, panel), today's closed incidents below as one line each, counts per group, a filter, a link per dashboard at the incident's time, a connection light. Reconnects by itself and catches up |
| **Notify** | When an incident opens, the page can show a desktop notification and play a short sound (the user switches each on once in the browser) |
| **Show its state** | Health: viewers connected, incidents known, changes received, updates merged for slow viewers, age of the last change |

## How we will know it works (no Datadog needed)

| Check | Expected |
|---|---|
| Live update | An incident opened by the Detector appears on an open page within 5 seconds; its close too |
| Catch up | A page that was closed for 10 minutes shows every incident of the day, including ones opened and closed meanwhile |
| Stalled viewer | One viewer that reads nothing for a minute does not delay others; when it reads again it gets the latest state, not hundreds of old updates |
| Restart | After a Notifier restart, pages reconnect by themselves and show the same open incidents |
| Demo | The 42-minute replay (with `day-by: received`) or live traffic on the page: problems pop up every 8 minutes, close, and stay listed for the day |
| Time zone | Switching the shared zone to `America/New_York` moves the page's times and day without a restart |

## Capabilities

### New Capabilities
- `live-alerting`: showing incidents live on a web page, catching up on connect, protecting the system from slow
  viewers, and linking each incident to its evidence.

### Modified Capabilities
None. `incident-events`, `IncidentEvent` and the RSocket route `incidents.live` already exist (add-contracts);
the Detector already puts dashboard links in each incident.

## Decision: the browser library (user, 16 Nov 2026)

First choice (option A): a pinned copy of `rsocket-js` kept in the repo. Checking it showed that `rsocket-js`
(BSD-3-Clause for its stable 0.0.x versions; 1.0 is still alpha) publishes no ready-made browser file, only separate
modules that need a JavaScript build tool. The user then chose the fallback of design N6: **a small hand-written
RSocket client** in the page (no download, no install), tested against the real Spring server. Rejected: copies
pre-bundled by a CDN (third-party builds), and installing Node.js and esbuild (a toolchain for one file).

## Lab limits (user, 15 Nov 2026)

- Nothing is stored: the Notifier keeps only the current incidents in memory (a few hundred at most) and reads
  the rest again from Kafka after a restart.
- The page holds one day: closed incidents leave at midnight in the shared time zone. Kafka keeps the events 2 days, enough to rebuild today after a restart; about 2 MB of memory on a busy lab day.

## Out of scope

| Not in this change | Where |
|---|---|
| The daily Markdown report | `add-reporter` |
| Sign-in, users, sharing the page outside this machine | Not needed for a lab |
| Slack, e-mail or phone alerts | Later; the page and desktop notifications first |
| Acknowledging or silencing incidents from the page | Later; would need a way back to the Detector |

## Impact

- `app/notifier`: Kafka reader, incident state, per-viewer buffers, RSocket route, the page (HTML, CSS, a small
  script), health, tests.
- `config-repo/notifier.yml`: closed-incident time on the page, buffer size, port. No code change to tune them.
- No new Kafka topic, no database, no contract change.
