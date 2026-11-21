# Verification: add-datadog-look

21 Nov 2026. The pages look and work like Datadog's dashboards (our own page: no Datadog logo, name, font or icons).

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **303 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | passed | `HistoryBatchRequest.interval_ms`, `method` (Rollup), `View.widget_id`: new fields only against the last pushed baseline |
| Collector | 123 passed | Every view carries its widget id (`PanelOrderTest`); recordings without it still replay |
| Notifier | 31 passed | Auto interval: 15m and 30m → 10 s, 1h → 20 s, 3h → 1 m, 6h → 2 m, 12h and 1d → 5 m (at most 300 points); method from the query (its own `.rollup(…)`, `.as_count()` → sum, else average; our `.rollup(max, 10)` ignored); one Store call per method used |
| Detector | 68 passed | A restored waiting incident is announced at once, then every 12 hours |

## Live (this machine, with the local test feed for the lab dashboard)

| Check | Result |
|---|---|
| Intervals from the Store | 15m: 10 s; 1h: 20 s; 6h: 2 m; 1d: 5 m (computed with `time_bucket` in the database) |
| Lab dashboard (positions in its JSON) | Coloured group bars from the JSON, widgets at their sizes, the status-code widget as stacked bars (its `display_type`), "Success Rates" as a split group (one graph per `api`), variable chips (`env: prod`), picker with "10s interval" and Live |
| Swagstore dashboard (no positions) | Groups full width, query values as big numbers, timeseries in order; red edges and bands from real open incidents |
| Crosshair | Hovering "Latency" shows the same moment (11:42) on every graph; the tooltip only on the hovered one |
| Full screen | Large graph and a min/max/avg/value table, sortable and filterable |
| Side panel | Graph, details (metric, question, tags, shown on, region, unit, kind, signal, normal, newest in three zones), anomalies of the last 24 hours |
| List | Per-region dashboard list with search, widget counts, badges |
| Dark mode | Follows the system (screenshots in both) |
| Memory (headless Chrome, 1440×900) | 20 dashboards × 40 widgets × 10 lines (8,000 lines, all held at once by the test) scrolled through: at most 64 charts alive, 24 at the end; **169 MB after garbage collection** (target 200 MB); most of it is the test's 2.4 million points held at once — a real page holds only the open dashboard's lines |

## Found and fixed during the check

| Seen | Fix |
|---|---|
| Past 1 Day answered nothing | The range start was rounded down, making the window longer than the Store allows; now rounded up |
| The crosshair did not move other graphs | The graphs' group was never connected (`echarts.connect`) |
| Every graph showed a tooltip while hovering | Only the hovered graph shows it now, like Datadog |
| Full-screen headers misaligned, titles in capitals | Number headers right-aligned; titles in normal case |

## Not shown live

| What | Why |
|---|---|
| Exact matching by widget id | These lines were described before the field existed; the Collector re-describes them when Datadog answers with data (none is sent to the trial now). Until then widgets are matched by group and title; the Collector test covers the id |
| Side-by-side with the user's own Datadog dashboards | Needs the user's screens; palettes and sizes are approximations by design |
