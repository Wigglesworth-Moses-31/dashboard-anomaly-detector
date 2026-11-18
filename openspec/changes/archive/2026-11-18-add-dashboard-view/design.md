# Design: add-dashboard-view

## Context

| Fact | Where |
|---|---|
| Each line's description (`SeriesDescriptor`) names its views: dashboard id and title, panel title, group (`panel_path`); its region is `source.instance` (the site) | `metric_series.proto` |
| The Store keeps every description (`metrics.series`, 452 rows here), raw 10-second points and per-minute summaries; `GetHistory` answers one line | `store` |
| The Notifier serves the page, streams incidents over RSocket, keeps a 2-day index of each line's incidents (`LineHistory`) | `notifier` |
| Regions and their sites come from `regions.yml` (`/settings/state`) | add-settings-page |

## Decisions

### D1. Contracts

```proto
message View { … int32 group_index = 5; int32 panel_index = 6; }   // Datadog's order; 0 = unknown (old lines)
service StoreService {
  rpc ListSeries (ListSeriesRequest) returns (stream SeriesDescriptor);   // every known line
  rpc GetHistoryBatch (HistoryBatchRequest) returns (stream HistoryChunk); // many lines, one range
}
message HistoryBatchRequest { repeated string series_ids = 1; int64 from_ms = 2; int64 to_ms = 3; Resolution resolution = 4; }
```

New fields and calls only; the compatibility check passes. At most 1,000 lines per batch call.

### D2. The catalogue (Notifier)

At start and every minute, `ListSeries` from the Store builds an immutable catalogue: site → dashboard view →
group → panel → lines, sorted by `group_index`, `panel_index` (then title). Swapped in one reference (copy-on-write):
readers never lock. O(lines log lines) per rebuild; 452 lines here. `GET /dash/catalogue` answers it without values.

### D3. Ranges and thinning

`GET /dash/range?view=<id>&range=1h&width=600` → one `GetHistoryBatch` for every line of the view → per line, the
range is cut into `width` columns and each column keeps its **lowest and highest** point, in time order (min-max
thinning, O(points)). 15m and 1h use raw values; 4h, 1d and 7d per-minute ones. Answer: per line `[t, v]` pairs.
`GET /dash/line?id=` does the same for one line (the Metric page).

### D4. Live values only for what is on screen

The Notifier reads `metric-points` from Kafka (latest offset, its own group). A page opens one Server-Sent Events
stream `GET /dash/live?lines=a,b,c` naming the lines of the panels on screen (re-opened, debounced 500 ms, when that
set changes). Per stream, a map line → newest point (latest wins, so a slow page never builds a backlog); a single
scheduler thread flushes every second. Fan-out by an index line → streams. Memory per page: O(lines on screen).

### D5. The page

| Part | How |
|---|---|
| Router | Hash routes: `#/region/<name>`, `#/panel/<view>/<panel>`, `#/metric/<line>`, `#/settings` |
| Charts | Apache ECharts (Apache-2.0) bundled in `static/vendor/`, canvas renderer, `animation: false`, `sampling: 'lttb'` off (the server thins) |
| Lazy charts | `IntersectionObserver` with a 300 px margin creates a chart when a panel comes near the screen; `dispose()` when its dashboard collapses or it is 3 screens away |
| Live | The visible panels' lines form the live set (D4); new points are appended and points older than the range dropped |
| Anomalies | Incidents still arrive on the RSocket stream; an index line → incidents feeds shading (`markArea`), badges and the attention counter |
| Metric page | Graph (`/dash/line`), details from the catalogue, anomalies from `/history?line=` and the live incidents; the reason box posts to `/incidents/{id}/reason` with `line` as before |
| Collapsed state | `localStorage` per dashboard, wrapped in try/catch |

### D6. Settings

`notifier.yml` → `anomaly.notifier.dashboards`: `ranges` (default `[15m, 1h, 4h, 1d, 7d]`, each at most 7 days),
`points-per-line` (600), `lines-per-panel` (20), `catalogue-refresh` (1m).

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| ECharts is about 1 MB | Loaded once and cached; memory is dominated by charts, which are lazy (D5) |
| A view with thousands of lines | `lines-per-panel` on the region page; the Panel page asks for one panel |
| Old lines without order fields | Ordered by title after the known ones |

## Migration Plan

Deploy contracts, Collector, Store and Notifier together. Old descriptions get the order fields at the Collector's
next discovery. The Incidents page is gone after the Notifier is updated.
