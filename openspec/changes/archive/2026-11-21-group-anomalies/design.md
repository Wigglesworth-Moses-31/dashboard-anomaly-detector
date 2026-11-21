# Design: group-anomalies

## Decisions

| Decision | How | Cost |
|---|---|---|
| Data | The incidents the page already holds (stream snapshot and live changes); the line catalogue gives each line's site and dashboards | no new call |
| Region and dashboard of an anomaly | From its lines' places in the catalogue (site, dashboard); one entry per (site, dashboard) it touches | O(lines) |
| Service | `group_key` without the site prefix (`datadoghq.com|api=/v1/invoices` → "api /v1/invoices"); upstream tags read "upstream …"; a dashboard group reads "(no service tag)" | O(1) |
| Tree | Built by a pure function `groupAnomalies(incidents, filter)` into nested maps, then sorted: O(n log n) | rebuilt at most once per frame |
| State | open; needs a reason (calm, a non-BAU line without a reason); closed; past (found in backfill); BAU-only anomalies count as closed | O(lines) |
| Route | `#/anomalies` (`?state=open,waiting`); the counter links there | |
| Remembered | Collapsed levels and the chosen states in `localStorage` (try/catch) | |
