# Proposal: add-dashboard-view

## Why

The live page shows only what went wrong. The user asked (16 Nov 2026) to "see all the metrics under a dashboard
irrespective of incidents": open the page, pick a watched dashboard, and see every panel and every line of it,
moving live, without opening Datadog. It also makes the system's work visible: the incidents the Detector caught
are shaded on the very lines it watched.

Every value is already kept. The Collector already sends each line's dashboard and panel (`metric-series`) and its
10-second values (`metric-points`); the Store keeps them for 2 days. Nothing new is asked of Datadog.

## What Changes

| Step | In plain words |
|---|---|
| **A second tab** | `http://localhost:8104` gets two tabs: **Incidents** (today's page, unchanged) and **Dashboards** |
| **Pick a dashboard** | The Dashboards tab lists every watched dashboard by title; choosing one shows all its panels |
| **Datadog's groups and order** (user, 16 Nov) | Panels appear under their Datadog group headings, in Datadog's order, in a simple grid that also fits a narrow screen. Exact sizes and positions are not copied |
| **Every line of every panel** | Each panel is a line chart of all its lines (for example one line per `status_code`), with a legend, the unit, and the newest value. Panels without metric queries are not shown, as today |
| **Time range picker** (user, 16 Nov) | 15m, 1h, 3h, 1d, 2d. Up to 3 hours: the 10-second values. 1d and 2d: the per-minute summaries (raw values are cleaned after each report, and a minute is plenty at that width) |
| **Live** | The panels move by themselves: each new 10-second value is pushed to the open tab as it arrives, the same way incidents are pushed today. Nobody polls |
| **Incidents shaded** (user, 16 Nov) | Where an incident covered a line, its time is shaded on that panel, and hovering the shading names the incident |
| **Keeps spikes visible** | A 2-day line has 2,880 minutes; a chart is ~600 pixels wide. The Notifier thins each line to one **min and max** pair per pixel column (not the average), so a one-step spike is never smoothed away. O(n) in the points read |
| **One Store call per panel range, not per line** | Opening a dashboard of 40 panels and 200 lines asks the Store once for all of them (a new batch call), not 200 times |
| **Settings** | `notifier.yml` → `dashboards`: the ranges offered, the default range, most points per line, Store address. No code change to tune them |
| **Health** | Dashboards opened, Store calls and their slowest answer, live points pushed, viewers on the Dashboards tab |

**One difference from Datadog, on purpose:** the Collector keeps the *highest* value of each 10-second step (so short
spikes survive), while Datadog's own graph often shows an average. Peaks on our panels can therefore be higher
than on Datadog's. The page says so in one line under the picker.

## How we will know it works (no Datadog needed)

| Check | Expected |
|---|---|
| All panels | The replay's two dashboards show every panel the Collector watches, under the same group headings and in the same order as in Datadog |
| Live | With the 15m range open, each panel gains a new step every 10 seconds |
| Ranges | 15m, 1h and 3h show 10-second steps; 1d and 2d show per-minute values, labelled "per minute" |
| Spike kept | A line with one 10-second spike among 2,880 calm minutes still shows the spike at the 2d range |
| Incidents | A replay incident on `/v1/invoices` latency appears as shading on the Latencies panel at the right time |
| Cost | Opening a dashboard: one batch Store call, under 1 second for 200 lines over 3 hours; a stalled tab never slows other viewers or the incident stream |
| Store stopped | The Dashboards tab says "history unavailable"; the Incidents tab keeps working |

## Capabilities

### New Capabilities
- `dashboard-view`: showing every watched dashboard's panels and lines live on the page, with a time range,
  thinning that keeps peaks, and incident shading.

### Modified Capabilities
- `message-contracts`: `View` gains the group title and the group and panel order (new fields only, safe for old
  readers); the Store gains a batch history call for many lines at once.
- `metric-storage`: the Store answers that batch history call.
- `metric-collection`: the Collector fills the group title and order when it reads a dashboard.

## Lab limits

- The Notifier becomes a Store client (gRPC 9102) and also reads `metric-series` and `metric-points` from Kafka.
  226 lines every 10 seconds is ~23 values per second: trivial.
- Memory: only the line list (a few hundred) and, per open tab, the newest values waiting to be pushed (latest-wins,
  as for incidents). Charts are not kept in the Notifier.

## Out of scope

| Not in this change | Why |
|---|---|
| Exact Datadog sizes and positions | User chose groups in order; exact layout breaks on narrow screens |
| Panels without metric queries (logs, APM, events, notes) | The system never collects them |
| Zooming, custom time ranges, comparing two days | Later, if wanted; the picker covers the lab's needs |
| Editing dashboards or creating anything in Datadog | This system only reads Datadog |
| Dashboards that are not watched | Only what the Collector watches is in the Store |

## Impact

- `app/contracts`: new `View` fields; new `GetHistoryBatch` Store call.
- `app/collector`: fills group title and order from the dashboard definition.
- `app/store`: the batch call (one query over many lines, streamed in chunks).
- `app/notifier`: reads lines and points from Kafka, a dashboard catalogue, the range endpoint, min-max thinning,
  a live points route, health, tests; `static/` gets the Dashboards tab (inline SVG charts, no library).
- `config-template/notifier.yml`: the `dashboards` block.
- No new Kafka topic, no database change, nothing in Datadog.
