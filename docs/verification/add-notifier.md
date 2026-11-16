# Verification: add-notifier

16 Nov 2026. Build: `mvn test` green, **162 tests** (contracts 28, service-kit 6, collector 56, store 15, detector 45,
notifier 12); live tests run with `-Dlive.notifier=true` against the running Notifier. The page was checked in the
browser pane at `http://localhost:8104`.

## The RSocket client (hand-written, user decision 16 Nov)
`rsocket-js` publishes no browser-ready file (separate modules that need a build tool; stable 0.0.x is BSD-3-Clause,
1.0 still alpha). The page uses `static/rsocket.js` (about 200 lines: SETUP, REQUEST_STREAM with routing metadata,
REQUEST_N, PAYLOAD, KEEPALIVE, ERROR, CANCEL). Against the real Spring RSocket server it connected, streamed 745
incidents, kept the connection through the whole session, and reconnected after every Notifier restart.

## Speed and memory (task 4.2), 2,000 incidents through the page's own code path (`__bench`), 3 runs
| Measure | Target | Result |
|---|---|---|
| First paint: build the list and lay it out | 1 s | **16-19 ms** |
| A burst of 50 changes | one frame, 16 ms | **0.3-0.4 ms** |
| Filter ("billing", 800 of 2,000 shown) | 100 ms | **1.0-1.2 ms** |
| Script memory | 30 MB | **6 MB** |
First attempt measured "about 1,000 ms": the browser pane was hidden, so the browser gave the page one animation frame
a second. Measured again by forcing the layout instead of waiting for a frame (the real work).

## Network checks (task 3.1, `NotifierLiveTest`)
| Check | Result |
|---|---|
| Kafka to RSocket client, worst of 20 changes | **2 ms** (target 5 s) |
| A client that asked for nothing while 20 updates arrived | the reading client got all 20 at once; the stalled one later got **1 line, the newest** (sequence 20, closed) |

## Whole pipeline and the page (tasks 4.3, 5.x)
| Check | Result |
|---|---|
| Replay of `recordings/run-1` through Collector, Kafka, Detector, Notifier, with the page open | Detector sent 21 changes; the Notifier accepted 21; incidents today 741 -> 745 |
| A busy day | 745 incidents rebuilt from Kafka at start (`day-by: received` during the demo), all shown |
| Light and dark | both follow the system setting (checked by emulating each) |
| Narrow window (559 px) | lines wrap into two columns, cards wrap |
| Keyboard | `/` focuses the filter, "billing" shows 94 of 745, `Esc` clears |
| Group chips | "billing 61", "Lab: API platform (skeleton structure) 57", "orders 56" ... (fixed: labels may contain ": ") |
| Dashboard link | `app.datadoghq.com/dashboard/w2f-pqs-ghd` from 5 min before the start to 5 min after the last change, new tab |
| Notifier stopped with the page open | page shows "reconnecting" and keeps all 745 incidents on screen |
| Notifier started again | page "connected" by itself, 745 incidents, each once |
| A test incident opened and closed while no page was connected (0 viewers) | on returning, the page shows it, closed |
| Shared time zone committed as `America/New_York` | within 8 s, no restart: Notifier day 2026-11-15 New York; page "Today · 2026-11-15 · America/New_York"; 23:06:20 India shows as 12:36:20 |
| Time zone typo `Asia/Kolkatta` | rejected ("not a known time zone"), New York kept; restored to Asia/Kolkata: day 2026-11-16 |

## Spec scenarios (live-alerting)
| Scenario | Proved by | Result |
|---|---|---|
| Incident opens / closes | `NotifierLiveTest` (2 ms); replay with the page open | **Pass** |
| Laptop wakes up | page away (0 viewers) while a test incident opened and closed; shown closed on return; `HubTest` snapshot then changes | **Pass** |
| Repeat after a Detector crash / old event late | `BoardTest.aRepeatAndAnOlderLateEventChangeNothing`, `HubTest.aChangeDuringTheSnapshot...` | **Pass** |
| Notifier restarted during an incident | live restart with the page open: reconnected, 745 once | **Pass** |
| One stalled browser tab | `HubTest.twentyUpdates...`, `NotifierLiveTest` (1 line, newest) | **Pass** |
| Reading an incident on the page | screenshots: signal, peak, normal with unit, meter, panel, dashboard link | **Pass** |
| A busy day / midnight | `BoardTest.theWholeDayStays...`, `BoardTest.atMidnight...`, `HubTest.midnightEndsEveryStream...` | **Pass** |
| Switch to New York time / a typo in the zone | live commits (above); `BoardTest.switchingToNewYorkMovesTheDay` | **Pass** |
| Replay of 15 November during 16 November | `BoardTest.dayByReceived...`; live demo with `day-by: received` | **Pass** |
| Which API broke most / find one incident | group chips and filter in the browser | **Pass** |
| A very busy day / filter a busy day | `__bench` (above) | **Pass** |
| Which problem is worse | ratio meters (11× longer than 3×) in the screenshots | **Pass** |
| New problem while working elsewhere / page opened during an incident | code path: notifications only for `EVENT_TYPE_OPENED` after `snapshot_complete`, one per burst | **Not seen live**: needs the user to allow notifications in the browser once |
| Notifier stopped | "reconnecting" within seconds, last incidents kept | **Pass** |
| Event without an incident | `ContractChecks` before the board; `eventsSetAside` on the health page (0 in all runs) | **Pass** (code and counter; no broken event was produced) |
| Two pages open (health) | health shows `viewers` (1 with the page, 0 while away, 2 during `NotifierLiveTest`) | **Pass** |

## Found and fixed while verifying
1. **Group labels cut at the first ": "**: dashboard-grouped titles contain one ("Lab: API platform ...: latency up").
   Now cut at the last ": ".
2. **Replay demo days**: with `day-by: received` the page itself dropped old-dated incidents. The end-of-snapshot
   line now also says how days are counted (`day_by`).
3. **A misleading first-paint number** (hidden pane throttles animation frames): measured with a forced layout.

## Known limits
- 146 incidents from yesterday's test runs stay "open" for ever: their close was never sent because the Detector's
  tables were cleared between test runs. Real data, not a page fault; they leave when Kafka drops them (2 days).
- Desktop notifications were not seen live (they need one click to allow them).
