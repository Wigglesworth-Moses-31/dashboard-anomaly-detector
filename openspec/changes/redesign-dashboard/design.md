# Design: redesign-dashboard

## R1 Catch-up (Collector, Store, contract)

**Contract.** `rpc GetNewest (NewestRequest) returns (stream SeriesNewest)`; `SeriesNewest { series_id, newest_ms }`.
Additions only, so the compatibility check passes after the baseline update.

**Store.** `SELECT series_id, max(ts) FROM metrics.points WHERE ts > now() - interval '25 hours' GROUP BY series_id`:
one pass over at most a day of raw points (the index is `(series_id, ts)`), asked once per Collector start.

**Collector.** `StoreViews.Collected` gains `default Map<String, Long> newest()`: "site|view" → the newest of its
lines' newest values (a view's gap starts where its freshest line stopped; a line that left the dashboard stays old).
A marker key says the Store answered, so a view missing from the answer (nothing in 25 hours) is filled for the full
span, and an older Store without the call causes no catch-up. `StoreViews` joins `ListSeries` (lines → views) with `GetNewest`.
`planBackfill` already picks units whose views are all new; it now also picks units whose views have a newest time
older than `now − backfill` (the history the first live poll fetches, 10 minutes), with a start per unit:
- new view: `now − backfill-new-views`;
- collected view: its newest time rounded down to the step, but never before `now − backfill-new-views`.
Only units never polled in this run are planned, so catch-up happens once per start. `runBackfill` takes a start
per unit: windows run from the oldest start, and a window asks only the units whose start is before its end. Units are
held from live polling until their windows are done (the Detector needs values in time order). Values go out as
backfill, as today.

Progress uses the existing `backfills()` map; the Notifier's health already passes it to the page. The page shows
"Catching up <span> · n%" in the top bar while it is not empty.

## R2 Sizes and zoom (page)

Every size is in CSS px, multiplied by `--z` (graph zoom, 0.75–2, `localStorage` `graph-zoom`) and by density
(Comfortable 1, Compact 0.8):
- a group's grid is `repeat(auto-fill, minmax(calc(180px * var(--z)), 1fr))`; a widget gets a fixed size per JSON
  column, about 110 px × zoom, rounded to grid columns: `span = min(columns that fit, max(2, round((width × 110 + 8) /
  188)))` (numbers and notes at least 1), recomputed by a `ResizeObserver` per grid. A share of the window was tried
  first and made widgets bigger when zooming out. JSON order is kept;
- heights are rows of 72 px × zoom, at least 3 rows for a graph and 2 for a number or note, so no graph is squashed;
- split groups: `minmax(calc(320px * var(--z)), 1fr)`, chart height `calc(160px * var(--z))`.
Browser zoom changes CSS px, so it changes how many fit per row with no extra code. A `ResizeObserver` per chart
calls `chart.resize()` (throttled to one frame), so redraws follow zoom at once; before the relayout the page notes the
widget at the top of the view and scrolls back to it afterwards.

## R3 Colours (page)

- Bands: ECharts `markArea` only for the anomaly's span, 12% opacity of red, amber or grey; no panel background tint.
- Title dot: `.w-dot.open/.waiting/.past`; `.red-edge` is removed.
- Palette: 10 colours from the Okabe-Ito set extended with two blues, 1.5 px lines.
- Axis: `splitNumber: 3`, a short formatter (`1.2k`, `99.8%`, `350 ms`), `hideOverlap: true`.
- Titles: middle ellipsis in JS (keep the first segment and the last two path parts), full title in `title`.

## R4 Top bar and navigation (page)

A sticky `header.topbar` replaces the region entries in the left bar:
`[US | EU]` › `dashboard picker` (an input with a dropdown list, filtered as you type, each entry with its open count)
› breadcrumb (group › widget when in full screen) · time range · Live · density · `− 100% +` · catching-up note.
Routes stay as today (`#/dashboards/<site>`, `#/dashboard/<id>?widget=&line=`), so Back works. The left bar keeps
Dashboards, Anomalies, Settings and the counter.

## R5 Worst anomalies strip (page)

Built from the incidents the page already holds, filtered to lines shown on this dashboard and to the chosen range.
Sorted by state, then size × minutes, largest first; size = the largest over its lines of peak / normal (or normal /
peak for a drop), with 10 points of a percentage counting as 1 ×. Anomalies that are open or need a reason are always
listed, even when they started before the range. Redrawn with the other
live updates (one per frame). A row click sets `?line=` and scrolls; the widget gets `only = {lineId}` (R6).
Group headers count anomalies of the lines inside them.

## R6 Show only one line (page)

ECharts legend with `selectedMode: true`, but the page handles `legendselectchanged`: a plain click selects only
that line (or all again when it was already the only one); Ctrl/Cmd-click toggles one. The state lives in the chart's
spec (`spec.only`, a Set), so a redraw keeps it. The side panel's table rows do the same.
