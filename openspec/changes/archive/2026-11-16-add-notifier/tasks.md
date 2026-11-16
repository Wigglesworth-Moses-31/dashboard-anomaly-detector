# Tasks

## 1. The RSocket client in the browser

- [x] 1.1 Check whether a browser-ready `rsocket-js` file exists (it does not), agree the fallback with the user (hand-written client, 16 Nov), write `static/rsocket.js` for the frames of design N6, and verify against the running Notifier: setup, the `incidents.live` stream, request-n, keepalive answered for several minutes, and a clean reconnect

## 2. Board and viewers

- [x] 2.1 Add the board of design N2 (latest wins by sequence, day buckets in the shared time zone, `day-by`, JSON made once) and verify by tests: a repeat and an older late event change nothing; yesterday's closed incidents leave at midnight while an open one stays; a zone change to `America/New_York` moves the day; `day-by: received` shows an old recording as today
- [x] 2.2 Add the viewer trays of design N4 (latest wins per incident, bounded, sends only on demand) and verify by tests that 20 updates to one incident while a viewer asks for nothing become one, in the newest state, and that a stalled viewer never blocks the others
- [x] 2.3 Add the Kafka reader of design N1 (all partitions from the beginning, no group, contract checks) and the catch-up of design N3 (snapshot, `snapshot_complete` line, then changes; re-snapshot at midnight and on a zone change), and add the `snapshot_complete` line to `docs/contracts.md`

## 3. Route, settings and health

- [x] 3.1 Serve `incidents.live` over RSocket from the board and the trays, and verify with a Java RSocket client: snapshot, `snapshot_complete`, then a change within 5 seconds; a client that asks for nothing for a minute does not delay another client
- [x] 3.2 Add the settings of design N7 with validators and rewrite `config-repo/notifier.yml`, and verify by tests that bad values are rejected and that an unknown shared time zone is rejected
- [x] 3.3 Add the health details of design N8 and verify them on `/actuator/health`

## 4. The page

- [x] 4.1 Build the page of design N5 and N5b (summary, open in full, closed one line each and expandable, group counts, filter, colours and meters, light and dark, reconnect with backoff, desktop notification and sound only for new incidents after the snapshot)
- [x] 4.2 Verify the page's speed and memory with 2,000 incidents through its normal code path (test hook): first paint within 1 second, a burst of 50 changes within one frame, the filter within 100 ms, script memory under 30 MB, and record the measurements
- [x] 4.3 Verify the page in the browser: light and dark, a narrow window, keyboard (`/`, `Esc`), the dashboard links open Datadog at the incident's time

## 5. Live check

- [x] 5.1 Run the Detector and the Notifier with the Collector (replay with `day-by: received`, or live traffic) and verify on the page: incidents appear within 5 seconds of their event, close, and stay listed for the day
- [x] 5.2 Verify catch-up and restarts: a page closed for 10 minutes shows everything of the day on reopening; after a Notifier restart the page reconnects within 10 seconds and shows the same incidents once
- [x] 5.3 Verify a zone change without restart: set the shared time zone to `America/New_York`, check the page's day and times follow, then restore Asia/Kolkata

## 6. Documentation and checks

- [x] 6.1 Map every scenario in `specs/live-alerting/spec.md` to the test or run that proves it, and record the results in `docs/verification/add-notifier.md`
- [x] 6.2 Update `HANDOFF.md` and `docs/platform.md` (how to open the page, its settings) and verify the documented steps work

## Workflow follow-up

- The user approved the proposal, spec and design on 16 Nov 2026 (whole day, time zone setting, efficient page) and asked to build.
- After the tasks are done, the user explains the Notifier back in a few sentences; then archive.
