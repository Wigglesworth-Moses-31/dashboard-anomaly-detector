# Tasks: redesign-dashboard

## 1. Catch-up
- [x] 1.1 Contract: `GetNewest`, `NewestRequest`, `SeriesNewest`; baseline updated
- [x] 1.2 Store: newest per line over the last 25 hours; live test
- [x] 1.3 Collector: `StoreViews.newest()`; `planBackfill` plans gaps; `runBackfill` with a start per unit
- [x] 1.4 Tests: 9-hour gap, 3-day gap capped, short restart, Store down

## 2. Page
- [x] 2.1 Sizes in px × zoom × density; auto-fill groups; split groups 320 × 160; ResizeObserver redraws; keep view
- [x] 2.2 Zoom control and density switch, remembered
- [x] 2.3 Colours: bands only on spans, title dots, palette, axes, middle-ellipsis titles
- [x] 2.4 Top bar: region switch, dashboard picker with counts, breadcrumb, range, Live, catching-up note; slim left bar
- [x] 2.5 Worst anomalies strip with ranking, row click, group counts, live
- [x] 2.6 Show only one line in legends and the side panel table

## 2b. Feedback of 22 Nov
- [x] 2.7 Opening a line highlights it on its graphs (bold, others faded, labelled band, peak dot); the panel has no graph and the page makes room
- [x] 2.8 A visible enlarge button on every graph, including each small graph of a split group

## 3. Finish
- [x] 3.1 Build, tests, restart; screenshots at 100%, 200% and 75%, light and dark; memory check
- [x] 3.2 README and `docs/verification/redesign-dashboard.md`
