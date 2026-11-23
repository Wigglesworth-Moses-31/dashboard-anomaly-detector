# Proposal: calmer-anomalies

## Why

The user (23 Nov 2026): "our app became more graph crowded which was not the aim in the beginning". And: "if we do
see a spike or dip for counts, success rates, latency … for every nth second update, the entire anomaly dashboard
gets polluted … increase in non 2xx is the high priority". And about the dashboard page: "I have Datadog, right?
Why re-invent the wheel?"

| # | Problem | Cause (checked in `detector.yml`) |
|---|---|---|
| 1 | **Too many graphs** | The page redraws whole dashboards, a copy of what Datadog already shows |
| 2 | **Anomalies page polluted** | Every line is judged every 10 s. A change lasting 2–3 steps (**20–30 s**) opens an anomaly, and 2 calm minutes close it again. A noisy count, success rate or latency line opens and closes anomalies all day |

## What Changes

### A. One page: the anomalies, as the exact graphs that had them, in the Incidents page's look
The user (23 Nov 2026): "the look and feel only of the web app should be as incidents page 16 - 18 nov, all new UI
advancements should be as latest". Agreed on the mock `docs/mockups/calmer-anomalies.html`.

| What | How |
|---|---|
| Look | The Incidents page of 16-18 Nov: its colours, top bar, the day's cards (today, open, need a reason, closed, longest) and the sections **Open → Needs a reason → Closed today** |
| Top bar | Page name, tabs **Anomalies · Settings** (no left bar), US · EU, time range, **− 100% +** zoom, live pill, 🔔 and 🔊 |
| Order | In each section, cards under a **Region › Dashboard** heading, worst first (size × time); closed rows newest first, with filter and chips |
| A card | Titled **Panel › Graph**, coloured edge by state, signal, line, peak, normal, bar, time, and how it was judged ("5-min average" or "every 10 s") |
| Unfolded | **That graph only, with all its lines**, the anomaly's band, its line bold, a dot at the peak. Show one line, enlarge and the shared crosshair stay |
| Context | Folded under the graph: the other graphs of the same panel, and "Calls" / "Called by" from `upstreams.yml` |
| Datadog | Every card has **Open in Datadog**: the real dashboard at the anomaly's time |
| Reason | The reason box on the card under Needs a reason, and in the line's side panel |

### B. Removed: the dashboard page
The page that copies whole Datadog dashboards goes: the widget layout as in Datadog's JSON, the density switch, the
dashboard picker, notes, tables and rings. **Zoom stays** on the Anomalies page: browser zoom (Cmd/Ctrl +/−) and the
**−  100%  +** buttons make its graphs bigger or smaller, as today. The density switch is not needed: zoom does the
same for a few graphs. Everything is still **collected and stored**; only the copy of the
dashboard is no longer drawn. The catch-up message ("Catching up 3 h · 40%") moves to the top of the Anomalies page.

### C. Two speeds of judging
| Signal | Judged | An anomaly opens when |
|---|---|---|
| **Errors (4xx, 5xx, gRPC ≠ 0), error rate** | Every 10 s, as today | The change lasts 2–3 steps (20–30 s). High priority: you hear fast |
| **Counts, success rate, latency, CPU/memory, unrecognised lines** | Every **5 minutes** | The 5-minute average is beyond its rule |

How a slow line is judged:

| Part | Value |
|---|---|
| Its value | The **average of its values in each 5 minutes**, on the clock (10:00–10:05, 10:05–10:10 …), judged when the 5 minutes are complete and settled |
| Its normal | The **median of the last 24 averages (2 hours)** |
| Its rule | The same limits as today (factor, points, wobble, smallest change), applied to the average. One average beyond the rule is enough |
| Calm | One average back within the rule |
| Silence | Unchanged: watched every 10 s |

Example: a success rate, normally 99%, dips to 60% for 30 seconds. Today that is an anomaly. Now the 5-minute
average is about 95% (4 points down), inside the 5-point rule: nothing opens. If it stays at 60% for 5 minutes, the
average is 60% and one anomaly opens.

**Cost:** a slow problem is shown up to **5 minutes after it starts** (plus the settle delay). Errors are not
affected. Why 5 and not shorter: the shorter the average, the more a short blip moves it. At 5 minutes a 30-second
blip is 1/10 of the average; at 2 minutes it would be 1/4, and most of today's noise would come back. The length is
a setting, so 3 or 10 minutes can be tried without code.

### D. Settings, outside the code
| Setting (in `detector.yml`) | Meaning |
|---|---|
| `judge-every` per rule | `10s` (every step, the default) or a longer time such as `5m`; rules without it keep today's behaviour |
| `slow-history: 2h` | How much history makes "normal" for slow rules |

The template sets `judge-every: 5m` on the success-rate, latency, saturation, traffic and `general` rules.
`scripts/check-config.sh` warns when `config-repo` lacks the new keys.

## How we will know it works

| Check | Expected |
|---|---|
| Short dip | A success rate dips for 30 s in the lab: no anomaly |
| Long dip | It stays down for 5 minutes: one anomaly, shown when that 5-minute average is complete, plus the settle delay |
| Errors | 5xx errors jump for 30 s: an anomaly within about 30 s, as today |
| Page | The Anomalies page shows only graphs with anomalies, each with all its lines; Open in Datadog opens the right dashboard and time; zooming in makes the graphs bigger |
| Fewer | Over 1 lab hour with the usual noise, the number of anomalies falls (counted before and after) |
| Setting | Changing a rule to `judge-every: 10s` and committing brings back today's behaviour, without a restart |

## Capabilities

### Modified Capabilities
- `anomaly-detection`: rules can be judged every 5 minutes on averages; normal for those is the last 2 hours.
- `dashboard-view`: the dashboard page is removed; the Anomalies page becomes the only page, drawing the exact graph
  of each anomaly with all its lines, its context and a link to Datadog.
- `runtime-configuration`: the two new settings.

## Impact

| Where | What |
|---|---|
| `app/notifier` static page | The largest part: the dashboard page is removed; the Anomalies page is redrawn as above |
| `app/notifier` `dash` | `/dash/range` (a whole dashboard) is removed; `/dash/panel` and `/dash/line` stay |
| `app/detector` | Rules gain `judge-every`; a line of a slow rule keeps 5-minute averages instead of 10-second values (less memory: 24 averages instead of 180 values) |
| `config-template/detector.yml`, `scripts/check-config.sh` | The new settings |
| Tests | The recorded scenarios for counts, success rate and latency change to 5-minute expectations |

No message contracts change. No data wipe. Open anomalies from before the change stay until they get a reason.
