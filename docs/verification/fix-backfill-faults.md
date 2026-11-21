# Verification: fix-backfill-faults (21 Nov 2026)

| Fix | How it was checked | Result |
|---|---|---|
| F1 Store refresh | The old and new SQL sent over JDBC to the lab database, exactly as the Store sends them | Old: `could not determine data type of parameter $1` (the error from the second machine). New: OK |
| F1 retry | Code: a failed refresh hands its range back to `StoreStats` before the loop's "retrying" path | Store tests 17/17 |
| F2 Detector window | 24 h of 10-second values (rising, falling, noisy, flat near 100%) through `Window` | Before: 8,640 heap entries for 180 live values. After: 235–366. New `WindowTest` checks ≤ 2 × 180 + 16 after every value and the median against brute force; Detector tests 69/69 |
| F3 Size log | Detector restarted | `Size: … lines watched, heap 64 MB used of 512 MB` once a minute |
| F4 Memory | `scripts/system.sh` | Every service starts with `-Xmx512m` |
| F5 Names | Headless Chrome, side panel of two formula lines without tags | "Success Rate" and "success %" (their aliases), no longer `lab.gateway.request.count`; no script errors |
| F5 Rollup | `DashTest`: ratios and `100 * (1 - errs / hits)` average; `query1 + query2` of sums sums; a function averages | Notifier tests 31/31 |
| F6 Old settings | A copy of the templates with the second machine's old values (`raw-safety-limit: 2d`, `max-database-size: 1GB`, `backfill-new-views: 2d`) | All three listed with both values and the file; this machine: nothing listed |

**Open (task 3.4):** the Success Rate panel near 0% on the second machine. Here the lab traffic is off and no panel
has that shape of query, so it is checked with that panel's widget JSON (names removed) once the user shares it.
