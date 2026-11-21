# Design: add-datadog-look

## Context

| Fact | Where |
|---|---|
| The exported JSON has `layout_type` (ordered or free), widget `layout` {x, y, width, height} on a 12-column grid (absent on auto-layout dashboards), group `background_color`, timeseries `display_type`, `style.palette`, `yaxis`, query value `precision`, `conditional_formats`, `autoscale` | the two exports here |
| The page can fetch a dashboard's committed JSON (`/settings/files/…`) and the region's files (`/settings/state`) | add-settings-page |
| The catalogue (`/dash/catalogue`) maps site → dashboard view → group → panel → lines; panels are matched today by group and title | add-dashboard-view |
| One Datadog widget can hold several requests; the reader makes one panel per request, same title | `DatadogDashboardReader` |

## Decisions

### K1. Exact widget matching: `View.widget_id`

```proto
message View { … int64 widget_id = 7; }   // the widget's id in the exported JSON; 0 = not known (older lines)
```

The reader keeps each widget's `id` on its panels (`Dashboard.Panel.widgetId`), `PollUnit.View` carries it, the
translator writes it. The catalogue's `Panel` gains `widgetId`. The page matches a JSON widget to its lines by
`widget_id`; lines without it fall back to group + title (as today). New field only.

### K2. Routes and frame

| Route | Shows |
|---|---|
| `#/dashboards/<site>` | The region's dashboard list (search box, title, widgets, badges) |
| `#/dashboard/<dashboard id>` | The dashboard: top bar + grid |
| `…?widget=<widget id>` | That widget full screen (over the dashboard) |
| `…?line=<line id>` | The side panel for that line (over the dashboard or the full-screen widget) |
| `#settings` | Settings (unchanged) |

A slim left bar (56 px, icons with labels on hover): Dashboards, one entry per region, Settings, the attention
counter. Browser back closes the side panel or full screen (they are part of the route).

### K3. The grid

| Case | How |
|---|---|
| Ordered layout with positions | CSS grid of 12 columns; a widget at `grid-column: x+1 / span width; grid-row: y+1 / span height`; one row unit = 56 px (close to Datadog's density at 1440 px wide) |
| Group | Its own nested 12-column grid; inner positions are relative to the group; a title bar in its `background_color` (vivid_blue, purple, gray…: our palette of those names), click to collapse |
| No positions (auto) | Widgets flow in order: timeseries 4 columns × 2 rows, query value 2 × 1, top list and table 4 × 2, groups full width |
| Narrow screen (< 900 px) | Every widget full width, in order |

### K4. Widget renderers (one function per type, all on the existing lazy-chart machinery)

| Type | Renderer | Data |
|---|---|---|
| `timeseries` | ECharts line, bar or area per request's `display_type`; colours from `style.palette` (palettes `dog_classic`, `cool`, `warm`, `purple`, `orange`, `gray` approximated); legend per `show_legend`/`legend_layout`; y-axis `min`/`max`/`scale` | `/dash/range` (as today) |
| `query_value` | Big number: last value, `precision`, `autoscale` (k, M), unit; colour from the first matching `conditional_formats`; a faint area trend behind | last point of the range |
| `toplist` | ECharts horizontal bars, highest first, top 10 | last value per line |
| `query_table` | HTML table: one row per tag combination, columns = its requests, sortable | last values |
| `sunburst` | ECharts pie with an inner ring per tag | last values |
| `split_group` | One small timeseries per value of the split tag (from the lines' tags), in a mini grid | as timeseries |
| `note` | Its Markdown as text (no HTML from the file) | the JSON |
| others (service map, logs, events, SLO, …) | Grey box "not collected by this system" | — |

Anomaly bands, red edges and grey for past and BAU come from the existing shading code.

### K5. Shared crosshair

Every timeseries chart of a dashboard joins one ECharts group (`echarts.connect`): the axis pointer and tooltip move
together. O(charts on screen) per pointer move (only near-screen charts exist).

### K6. Full screen and side panel

| Part | How |
|---|---|
| Full screen | The widget re-drawn in an overlay at 80 % of the window, with a legend table: line, min, max, avg, latest (computed from the points in range, O(points)), sortable and searchable; Esc closes |
| Side panel | 440 px from the right: details (from the catalogue and the newest anomaly), anomalies (from `/history` plus the live anomaly map), reason boxes (the existing code moved here); clicking another line swaps it |

### K7. Look

Design tokens for light and dark (`prefers-color-scheme`): Datadog-like neutrals, 1 px widget borders, 4 px radius,
13 px titles, 11 px axis labels, compact spacing. An accent of our own (not Datadog's brand purple), no Datadog logo,
name, font or icons.

### K8. Time and the auto interval (user, 21 Nov: "as Datadog")

| Range | Interval | Points |
|---|---|---|
| 15 m, 30 m | 10 s | 90, 180 |
| 1 h | 20 s | 180 |
| 3 h | 1 m | 180 |
| 6 h | 2 m | 180 |
| 12 h, 1 d | 5 m | 144, 288 |

Rule: the smallest of 10 s, 20 s, 30 s, 1 m, 2 m, 5 m with at most 300 points. The Store computes it in one query:
`GetHistoryBatch` gains `interval_ms = 5` and `method = 6` (AVG, SUM, MIN, MAX) and answers
`time_bucket(interval, ts)` with that aggregate over the raw values (24 hours are kept raw, so no per-minute table is
needed for the page). Method: the query's own `.rollup(method)` if it has one, else SUM for a count shown as counts
(`.as_count()`), else AVG (Datadog's defaults).

Live: at 10 s intervals the existing SSE values are appended; at longer intervals the dashboard's range is fetched
again every interval (at least every 30 s), one Store call per dashboard. Pause (❚❚) stops both; resume (▶) catches up.
The interval in use is shown on each graph ("2m interval"), and one line under the picker says each 10-second value is
the step's highest, so averages can be a little above Datadog's.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Palettes and sizes only approximate Datadog's | Side-by-side check with the user's Datadog dashboards; tokens adjustable in one CSS block |
| Older lines without `widget_id` | Matched by group and title until the Collector's next discovery |
| A widget type not handled | Falls back to a timeseries when it has metric requests, else the grey box |

## Migration Plan

Deploy contracts, Collector and Notifier together. The old region, panel and metric pages are replaced.
