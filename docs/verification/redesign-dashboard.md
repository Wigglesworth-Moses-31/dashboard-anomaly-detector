# Verification: redesign-dashboard (21 Nov 2026)

## Catch-up after a shutdown (R1)
| Check | Result |
|---|---|
| Collector tests: a view last saved 9 h ago | Filled from its newest value in 3 windows of 4 h (3 panels × 3 = 9 answers), then live |
| A view last saved 3 days ago | Only the last 24 h: 6 windows |
| A restart within a minute | Nothing filled |
| An older Store without the new call | No catch-up, as before (earlier backfill tests unchanged) |
| Store live test (`-Dlive.db=true`) | `GetNewest` gives each line's newest time; a line older than 25 h is not listed |
| Contract check | Passes; `GetNewest`, `NewestRequest`, `SeriesNewest` are additions; baseline updated |
| Live on this machine | Restarting the Collector logged "Catch-up: 2 views … in 7 windows"; the Lab view started at its newest value (11:47 UTC), the Swagstore view (nothing in 25 h) at 24 h ago. Health showed `backfill-since`, the page's top bar "Catching up …" |

## Page (R2–R6), headless Chrome on the Lab and Swagstore dashboards
| Check | 75% (1920 px) | 100% (1440 px) | 200% (720 px) |
|---|---|---|---|
| Split group graphs per row | 5 | 3 | 1 |
| One small graph (page px) | 349 × 160 | 428 × 160 | 583 × 160 |
| A width-4 timeseries (page px) | 393 × 232 | 371 × 232 | 597 × 232 |

On screen a 75% graph is about 0.75 × its page size and a 200% one 2 ×, so zooming in makes graphs bigger with fewer
per row and zooming out smaller with more per row. The graph size control at 125% made small graphs 200 px high and
widgets 522 × 286.

| Check | Result |
|---|---|
| Worst anomalies | Lab: 5 of 6 listed, all "Needs a reason", 114× first; Swagstore: 4 open first, then a waiting one |
| Row click | Scrolls to the widget, opens the side panel on that line |
| Legend click | 10 lines → 1 shown; Cmd-click another → 2 |
| Axis numbers | 3–4 ticks, never repeated (`145.2 … 146.4`) |
| Dots, bands | Red / amber / grey dots by titles; no red edges; light bands only over anomaly times |
| Light and dark | Both read clearly |
| Script errors | None |
| Memory | 20 dashboards, 800 widgets, 8,000 lines: 156 MB after garbage collection (169 MB before), at most 21 charts alive |

All tests: contracts 28, collector 126, store 18, detector 69, notifier 31, reporter 18, config-service 10; no failures.

## Open finding
The Lab dashboard's stored success rates for its last saved minutes read 142–149%, which a success rate cannot be.
Datadog has no lab data for that time any more, so it cannot be compared here. This belongs with the open Success Rate
check (fix-backfill-faults task 3.4), together with the user's own panel.
