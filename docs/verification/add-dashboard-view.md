# Verification: add-dashboard-view

18 Nov 2026. The Incidents page is replaced by live dashboards per region, a page per panel and a page per metric
(graph and details on top, its anomalies below).

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **293 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | 28 passed | `View.group_index`, `panel_index`, `ListSeries`, `GetHistoryBatch` pass the compatibility check against the old baseline; baseline refreshed on purpose |
| Collector | `PanelOrderTest` | Every view gets its group's and panel's position, in dashboard order, starting at 1; the replay comparison ignores the new fields (the 15 Nov recording predates them) |
| Notifier | 27 passed (`DashTest`) | Min-max thinning: one spike in two days survives, at most 2 points per column, time order kept; the catalogue keeps Datadog's order per site, and the same dashboard in US and EU stays two dashboards with different panel ids; live values go only to pages showing the line, newest wins; up to 1 h raw values, longer per minute |

## Live (this machine)

No data is being sent to the Datadog trial, so a lab-only test feed put steady values for the 93 lines of
"Lab: API platform" on the local Kafka `metric-points` topic every 10 s for 8 minutes (nothing in Datadog).

| Check | Result |
|---|---|
| Catalogue | From the Store (`ListSeries`): 452 lines, 2 sites, 2 dashboards each; groups show as one "Panels" group because these descriptions predate the group fields (filled at the next discovery with data) |
| Region page | US and EU tabs, Settings, the attention counter ("226 open · 1 needs a reason": the stale incidents of 15 Nov), both dashboards collapsed with badges; expanded: 10 panels as live line charts in order, red edges, shading |
| Live | `livePointsPushed` grew on the Notifier's health; charts moved every 10 s |
| Panel page | Breadcrumb, large chart, legend with state, newest and peak, sortable and filterable |
| Metric page | Graph and details (metric, question, tags, shown on, region, unit, kind, judged as, normal at the last anomaly, newest value in three zones); anomalies newest first with reason boxes, "same reason for all n lines" and links to the other lines |
| One Store call per dashboard | `/dash/range` for 93 lines: one `GetHistoryBatch`, slowest Store call 35 ms |
| Store stopped | `/dash/range` → 503 "history unavailable"; the page and the anomaly stream kept working; after the Store restarted, values came back |
| Memory (headless Chrome, 1440×900) | A synthetic region of 20 dashboards × 40 panels × 10 lines (800 panels, 8,000 lines), every dashboard expanded and scrolled top to bottom: panel frames in 79 ms, at most **40 charts alive** at once (13 at the end), JavaScript heap at most **54 MB** (target 200 MB) |

## Found and fixed during the check

| Seen | Fix |
|---|---|
| The US dashboard answered with no values | The same Datadog dashboard is watched in US and EU; ids now name the site (`datadoghq.com~w2f-pqs-ghd`), panel ids too |
| Panels looked solid red | Dozens of old anomalies stacked their shading; overlapping times are now merged into one band per colour |
| A silent line read "dropped to 0 (−100%)" | The anomaly's direction was read from the wrong field |

## Differences from the specs, on purpose or for later

| Spec | Now |
|---|---|
| "the rule that judges it, its normal value now" (Metric page) | The Notifier does not know the Detector's rules or current normal: the page shows the signal and the normal **at the line's last anomaly** |
| Reasons on the metric page | The same endpoint as before (tested in add-line-reasons); not exercised live here, because a test reason would close one of the user's real waiting incidents |
| ECharts "custom build with only line charts" (proposal) | The full 1 MB build is bundled (no build tooling needed); memory is dominated by charts, which are lazy |
