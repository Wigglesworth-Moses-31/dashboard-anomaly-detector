# Tasks

## 1. Contracts, Collector, Store

- [x] 1.1 `View.group_index`, `panel_index`; `ListSeries`, `GetHistoryBatch` (design D1); compatibility check, baseline, `docs/contracts.md`
- [x] 1.2 Collector fills the order fields; Store answers both calls; verify by tests

## 2. Notifier

- [x] 2.1 Catalogue from the Store (D2), `/dash/catalogue`; verify by tests
- [x] 2.2 `/dash/range`, `/dash/line` with min-max thinning (D3); verify by tests: a spike survives, at most 2 points per column
- [x] 2.3 Live points over SSE for the lines on screen (D4); verify by tests: latest wins, only subscribed lines
- [x] 2.4 Settings (D6) and health

## 3. Page

- [x] 3.1 Router, header (regions, Settings, attention counter, connection, 🔔 🔊), the Incidents page removed
- [x] 3.2 Region page: dashboards collapsible with badges, groups, lazy live charts, shading, red edge
- [x] 3.3 Panel page and Metric page (graph, details, anomalies, reasons)
- [x] 3.4 Memory: a synthetic region of 20 dashboards × 40 panels × 10 lines, measured

## 4. Checks and docs

- [x] 4.1 Live on the running system; README; `docs/verification/add-dashboard-view.md`; run all tests
