# Verification: add-reporter

16 Nov 2026. Build: `mvn test` green, **176 tests** (contracts 28, service-kit 6, collector 56, store 14, detector 44,
notifier 12, reporter 16), database tests with `-Dlive.db=true`. Live runs with the platform, the Store, the Detector
and the Reporter; reports in `reports/`.

## Live run: the first start catches up 14 and 15 November
Database before: 127,933 raw points; 115,446 before 15 Nov 23:00 India time; 12,487 from 23:00 on (the last hour of
the day); 22,778 per-minute rows for 15 Nov.

| Step | What happened |
|---|---|
| Store stopped, Reporter started | Nothing written, nothing removed; health DEGRADED: "a report has waited more than an hour", waiting `2026-11-14`, `2026-11-15`, last error `UNAVAILABLE` |
| Store started | Within a minute: `2026-11-14.md` (quiet day, cleaned) and `2026-11-15.md` (744 incidents) written and verified |
| Reporter killed with `kill -9` the moment 15 Nov was `verified` | The Store finished the removal already sent: **115,446** raw points removed (exactly those before 23:00) |
| Reporter started again | State `cleaned`, the clean-up repeated harmlessly (**0** removed), the report **unchanged** (same SHA-256, same file time), still 2 files |
| Database after | raw points **12,487** (the last hour kept), **0** before 23:00, per-minute rows of 15 Nov **22,778** (all kept) |

The 15 November report: headline "744 incidents (previous window 0) · longest 27 min · 144 still open", "Data: 226
lines, 6.6% of expected points — data incomplete" (only about 85 minutes of that day were recorded), the incident
table, one section per incident with peaks, normals, times normal and dashboard links at the incident's time, groups
that broke most (billing 61, lab gateway 57, orders 56, /v1/invoices 54 ...), lines that moved most (first: the
"Slowest calls (p95, ms)" panel, whose formula divides by 1000), and the 18 unclassified lines by panel. 744 incidents
because every test replay of 15 Nov re-detected the same recording under new ids: true to the data.

## Settings live, no restart (task 3.4)
| Commit | Result |
|---|---|
| `window: 6h` | settings version 2 within seconds; `POST /reports/2026-11-16` wrote `2026-11-16_00-06.md`, `_06-12.md`, `_12-18.md` (18-24 had not ended) |
| `window: 48h` | rejected: "must be 1h, 2h, 3h, 4h, 6h, 8h, 12h or 24h (a report covers at most a day)"; 6h kept |
| `window: 24h` | version 3, back to days |

## The Store's clean-up (`RawPointRemoverLiveTest`, synthetic data on 10 November, "now" passed explicitly)
| Check | Result |
|---|---|
| Asked "before 09:00" with now = 10:00 | Store used **07:00** (the 3-hour floor); 420 points removed; 180 kept; per-minute rows of 00:00-07:00 all kept |
| A time in the future | refused, nothing removed |

## Spec scenarios
| Scenario | Proved by | Result |
|---|---|---|
| End of the day / on request | catch-up wrote `2026-11-15.md` and `2026-11-14.md`; `POST /reports/2026-11-16` | **Pass** |
| Six-hour windows / too long | `ReportWindowTest`, `ReporterSettingsTest`; live commits | **Pass** |
| Day with incidents / quiet window | `IncidentReportTest` (4 incidents, previous 29, 1 open; quiet with previous 37); live 15 and 14 Nov | **Pass** |
| Reading one incident | `IncidentReportTest` (1,569 ms, normal 138 ms, 11.4×; −25 pts; link) ; live report sections | **Pass** |
| Which API broke most / unclassified lines | `IncidentReportTest`; live report | **Pass** |
| Restart during the report | `ReportJobTest` (crash at verified, then restart: same file, one clean-up); live `kill -9` at verified | **Pass** |
| Report fails | `ReportJobTest.aFailedWriteRemovesNothing` (folder not writable); live: Store down, nothing written or removed | **Pass** |
| Detector restarts just after midnight | the Store keeps the last 3 hours in any case (`RawPointRemoverLiveTest`); live: the last hour of 15 Nov kept | **Pass** |
| Points arriving during the clean-up | the job asks for at most `min(window end - 1 h, now)`; the Store never touches the last 3 hours | **Pass** (by design and test; no points were arriving during the live run) |
| Reporter down for a night | live: the first start reported 14 and 15 Nov, oldest first | **Pass** |
| The 31st report | rotation keeps the newest `keep-reports` (30) by name | **Pass** by code; not reached live (5 files) |
| A report is due (health) | health listed both waiting windows while the Store was down | **Pass** |
| The Reporter cleans the day (Store) / a time in the future / the safety limit | `RawPointRemoverLiveTest`; live removal of 115,446; the Store's 2-day retention policy is unchanged | **Pass** |

## Found and fixed while verifying
1. **Unclassified lines listed for a window without data** (the quiet 14 Nov report listed today's lines). Now only
   lines with values in the window are listed.
2. **A Store test's data** (`LiveDataCheckTest` of add-detector) is now skipped with a clear reason when the 15 Nov
   raw values have been cleaned, which the Reporter does by design.

## Known limits
- When the Reporter dies after the Store removed the points but before it saved the count, the state shows 0 removed
  for that window (the repeat removed 0). The data is right; only the counter is lost.
- The report lists every incident the Detector announced, including repeats from test replays.
