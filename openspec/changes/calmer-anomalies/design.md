# Design: calmer-anomalies

## Context

Today every line is judged on each 10-second value (`LineWatch.accept`), and a change of 2–3 steps opens an
incident. The page (`app/notifier/.../static`) draws whole dashboards. The user wants fewer anomalies for slow
signals, errors kept fast, and a page with only the anomalies' graphs in the look of the 16–18 Nov Incidents page
(mock: `docs/mockups/calmer-anomalies.html`).

## Decisions

### D1. Averages are made inside the line, not in a new service
A line whose rule has `judge-every` longer than a step keeps a running sum and count for the current period
(`floor(ts / period) × period`). When the first value of the next period arrives, the finished period's average is
handed to the same state machine as before, with the period's start as its time.

| Why | Alternative not taken |
|---|---|
| The state machine (spike, peak, calm, respike, silence) stays one piece of code, already tested | A new "rollup" topic from the Store: more moving parts, and the Store's 1-minute summaries are not aligned with "settled" |
| Values already arrive settled and in time order per line, so "the next period's first value" means the period is complete | A timer per line: needs a clock, while every decision uses the time of the numbers |

Cost: O(1) per value; memory per slow line is 24 averages instead of 180 values.

**Delay:** a period is judged when the next period's first value arrives, which is one step (10 s) after the period ends, plus the settle delay.

### D2. What stays on raw values
Repeats (`ts <= lastTs`), presence, and silence keep using every raw value. So silence is still found in 1 minute
and the dashboard watermarks are unchanged. Only "beyond the rule" moves to averages.

### D3. Limits for slow lines
| Limit | Fast line | Slow line |
|---|---|---|
| History for normal | `history` (30 min) | `slow-history` (2 h = 24 averages) |
| Judged after | `min-history` (10 min) | `slow-min-history` (30 min) |
| Calm | `calm-period` (2 min) after the first calm value | at once: the first average within the rule ends the spike (`CALMED`) |
| Steps | per rule | per rule, counted in averages (template: 1) |

### D4. Warm start and rule changes
- **Warm start:** the rule is matched **before** the warm start, so the engine asks the Store for `max(history, slow-history)` of a slow line. The values are summed into averages: finished periods go into the window, and the last unfinished one stays open for the live values.
- **Rule changes:** a settings change that moves a line between fast and slow clears its window and open period and marks it for a new warm start. The next message refills it from the Store. A line in an anomaly keeps its anomaly.

### D5. Settings
| Setting | Where | Rule |
|---|---|---|
| `judge-every` | each rule, and `general` | missing or `10s` = every step. Otherwise 1–30 minutes, dividing an hour (1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30) |
| `slow-history` | detector | default 2 h, at most 2 d, at least 3 × the longest `judge-every` |
| `slow-min-history` | detector | default 30 min, not longer than `slow-history` |

`check-config.sh` adds `judge-every slow-history slow-min-history` to the keys it compares, so an old `config-repo` is
pointed out at start.

### D6. The page
`index.html`, `app.js`, `look.css` and `app.css` are replaced by one page in the old look (the mock made real):

| Part | Data from |
|---|---|
| Day cards, sections, cards | The live incident stream (RSocket, as today) |
| Graph of a card | `/dash/panel` (one widget, all its lines) and `/dash/line`; live values from `/dash/live` for unfolded cards only |
| Other graphs of the panel | The same `/dash/panel` answer: its other split values |
| Calls / Called by | `/settings/upstreams` (as today) |
| Open in Datadog | The incident's dashboard link, at its time |

`settings.js` is unchanged except that it is shown as a tab. The code that drew whole dashboards (grid, layout,
density, Worst strip, dashboard picker) is removed. `/dash/range` is removed, while `/dash/catalogue` stays because it names the panels.

## Risks

| Risk | Answer |
|---|---|
| A slow anomaly shows up to 5 minutes later | Agreed with the user; errors stay fast. The setting can make it shorter |
| A short but deep latency spike is averaged away | Intended. The error lines catch failing requests fast |
| Recorded scenarios change | Scenario tests keep their old meaning by setting `judge-every: 10s` in their fixtures. New tests cover the 5-minute judging |
| Page rewrite breaks a feature | Each requirement of the dashboard-view delta is checked in headless Chrome against live lab data |
