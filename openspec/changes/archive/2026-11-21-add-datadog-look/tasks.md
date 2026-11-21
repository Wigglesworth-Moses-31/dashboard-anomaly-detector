# Tasks

## 1. Contract and Collector

- [x] 1.0 `HistoryBatchRequest.interval_ms`, `method`; the Store's bucketed batch query; tests (design K8)
- [x] 1.1 `View.widget_id`; the reader keeps widget ids; catalogue `Panel.widgetId` (design K1); compatibility check, baseline; tests

## 2. Page

- [x] 2.1 Left navigation, routes, dashboard list per region with search and badges (K2)
- [x] 2.2 Grid from the JSON: ordered positions, groups with colours, auto layout, narrow screens (K3)
- [x] 2.3 Widget renderers: timeseries (display type, palette, legend, y-axis), query value, top list, query table, sunburst, split group, note, not collected (K4)
- [x] 2.4 Shared crosshair (K5); full screen with legend table; side panel with details, anomalies, reasons (K6)
- [x] 2.5 Look: light and dark tokens (K7); time picker (15m, 30m, 1h, 3h, 6h, 12h, 1d), auto interval, live at every range with pause (K8)

## 3. Checks and docs

- [x] 3.1 Live with the test feed: both dashboards side by side with their JSON layout; screenshots in light and dark; memory measured again; README; `docs/verification/add-datadog-look.md`; all tests
