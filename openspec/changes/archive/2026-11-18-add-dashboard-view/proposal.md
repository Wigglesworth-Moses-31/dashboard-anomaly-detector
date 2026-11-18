# Proposal: add-dashboard-view

## Why

The user (18 Nov 2026): the incident page "is very clumsy, the tree doesn't look good". Instead, show **the actual
dashboards under each region**, the way Datadog shows them, **with live traffic**; clicking a panel shows it as in
Datadog; clicking one metric opens **its own page with the graph and details at the top and its anomalies at the
bottom**. The **Incidents page is removed** (user, 18 Nov): anomalies are seen where they happen, on the panels and on
each metric's page.

This replaces the earlier plan of this change (16 Nov: one "Dashboards" tab next to an unchanged Incidents tab) and
the planned `improve-incident-page`. The data plumbing of that plan stays (group and order of panels, one Store call
per dashboard, thinning that keeps peaks, live points).

Every value is already kept; nothing new is asked of Datadog.

## What Changes

### The pages

| Page | Address | What it shows |
|---|---|---|
| **Region** (one per region: **US**, **EU**) | `#/region/us` | The region's dashboards, each **collapsed or expanded** (remembered per browser). A collapsed dashboard is one row: title, number of panels, and badges: **● 2 open** (red), **● 1 needs a reason** (amber). Expanded: its **groups in Datadog's order** (each also collapsible), and in each group its **panels as live line charts** in a grid |
| **Panel** | `#/panel/<id>` | One panel large, as in Datadog: all its lines, a legend with each line's tags and newest value, sortable; anomaly times **shaded** on the chart; clicking a line opens its metric page |
| **Metric** | `#/metric/<line>` | **Top**: the graph with the range picker and shaded anomalies; details: metric and question, tags, dashboard › group › panel, unit, kind, the rule that judges it, its normal value now, newest value and when it arrived. **Bottom**: **its anomalies of the kept days**, newest first: when (UTC, IST, EST), how long, peak against normal, direction, state (open, needs a reason, closed), reason. A waiting one has its **reason box** here, and **"same reason for all n lines of this incident"** with links to the other lines |
| **Settings** | `#/settings` | As today |

The header has the region tabs, **Settings**, and an **attention counter** ("● 3 open · 2 need a reason"). Clicking
it lists those metrics, each linking to its metric page: without the Incidents page this is how a waiting anomaly is
found. 🔔 and 🔊 stay: a new anomaly that is not BAU still notifies.

### How panels are drawn

| What | Choice |
|---|---|
| Library | **Apache ECharts** (user, 18 Nov), a custom build with only line charts, shading, tooltip and legend, **bundled into the app** (Apache-2.0, nothing loaded from the internet) |
| Widget types | **Line charts for everything** (user): a "query value" or "top list" panel is shown as its lines over time |
| Time range | **Live 15m, 1h** (10-second values, moving); **4h, 1d, 7d** (per-minute values). 7d shows what is kept (2 days until `keep-one-week`) |
| Anomalies on charts | The time of each anomaly on a line is shaded; a panel with an open anomaly has a red edge; BAU anomalies are grey |
| Peaks | Each line is thinned to one **min and max pair per pixel column**, so a single 10-second spike is never smoothed away |

### Memory and speed with 20+ dashboards per region (user: "memory efficient when 20+ dashboards with heavy metrics")

| Rule | Effect |
|---|---|
| Dashboards start **collapsed**; a chart is created only when its panel **scrolls into view**, and released when its dashboard is collapsed or it is far out of view | Only the panels on screen cost memory, however many dashboards there are |
| **Live values are sent only for panels on screen**: the page tells the Notifier which panels it shows; the Notifier pushes only those lines (latest value wins for a slow tab) | 20 dashboards × 40 panels watched, but a screen of 12 panels receives 12 panels' values |
| At most **600 points per line** reach the browser (min-max thinning in the Notifier) | A 7-day line is 600 pairs, not 60,480 values |
| A panel shows its **top 20 lines** by peak in the range (the Panel page shows all, with a search) | A panel with 300 partner lines stays readable and light |
| **One Store call per dashboard and range**, not per line | Opening a 40-panel dashboard is one call |
| No animations; canvas drawing; one shared timer for live updates | Smooth with many charts |

Targets (measured and written down): opening a 40-panel dashboard under 1 s; browser memory under 200 MB with
every dashboard of a region expanded and scrolled through.

### What is removed

| Removed | Where it lives now |
|---|---|
| The Incidents page: open / needs a reason / closed lists, counts by group, the filter, the tree inside each incident | Badges on dashboards, shading and red edges on panels, the attention counter, and each metric's page |
| A line's "▸ history" | The metric page's anomaly list (the same 2-day, later 7-day history) |
| The reason boxes inside incidents | The metric page |

Not removed: everything behind the page. Incidents, reasons, BAU, notifications, the live stream and its catch-up
work as today.

## How we will know it works

| Check | Expected |
|---|---|
| Region page | US shows its dashboards collapsed, with badges; expanding one shows Datadog's groups in order and live panels |
| Live | At 15m every visible panel gains a step every 10 seconds; a collapsed dashboard receives nothing |
| Panel page | All lines with legend and newest values; an anomaly shaded at the right time |
| Metric page | Graph and details on top; its anomalies below in three time zones; giving a reason there works as before (per line, or for all lines of the incident) |
| Attention | "● 2 need a reason" lists exactly the waiting metrics; each link opens its page |
| Memory | 20 synthetic dashboards × 40 panels × 10 lines expanded and scrolled: browser memory and open time measured, under the targets |
| Peaks | One 10-second spike in 2 days still shows at the 7d range |
| Store stopped | Panels say "history unavailable"; live values and anomalies keep coming |

## Capabilities

### New Capabilities
- `dashboard-view`: region, panel and metric pages; live line charts with shading; ranges; peak-keeping thinning;
  memory rules.

### Modified Capabilities
- `live-alerting`: the Incidents page and its lists, tree, history button and reason boxes are removed; waiting
  anomalies are found through the attention counter and given reasons on the metric page; notifications unchanged.
- `message-contracts`: `View` gains the group title and the group and panel order; the Store gains a batch history
  call for many lines at once.
- `metric-storage`: the Store answers the batch call.
- `metric-collection`: the Collector fills the group title and order.

## Out of scope

| Not in this change | Why |
|---|---|
| Datadog's exact sizes and positions, and its other widget types | Line charts in Datadog's group order (user, 18 Nov) |
| Zoom by dragging, comparing two periods | Later, if wanted |
| Dashboards not watched; editing in Datadog | Only watched data is stored; the system only reads Datadog |

## Impact

- `app/contracts`: `View` fields; `GetHistoryBatch`.
- `app/collector`: group title and order.
- `app/store`: the batch call.
- `app/notifier`: line catalogue per region and dashboard (from `metric-series`), range endpoint with min-max
  thinning, live points for the panels a tab shows, the attention list; `static/` rewritten as the region, panel and
  metric pages with a bundled ECharts build.
- `config-template/notifier.yml`: ranges, points per line, lines per panel.
- README (the pages), and the archived Incidents-page requirements marked removed.
