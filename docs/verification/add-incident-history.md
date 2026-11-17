# Verification: add-incident-history

17 Nov 2026. The hierarchy view → group → panel → line → value, full text, no links on the page, and a line's
incidents of the last 2 days.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **246 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | 28 passed | `View.panel_path = 4`, `AffectedSeries.panel_path = 12` pass the compatibility check against the old baseline; baseline refreshed on purpose |
| Collector | 107 passed | A line's view carries its panel group; the replay fixture (messages of 15 Nov) is identical **except** for the new field: the comparison now drops `panel_path`, so any other difference still fails |
| Detector | 53 passed | An incident's line carries group and panel |
| `LineHistoryTest` | 4 passed | Three incidents of a line: "last time" and the list newest first; the latest event of an incident wins; older than 2 days dropped, open ones kept; rebuilt from the same events → identical; **"last time before this" is the newest incident that started before the opened one** (found in the browser, see below) |

## In the browser (real system, the incidents of 15 Nov, `day-by: received` for the check, then reverted)

| Check | Result |
|---|---|
| Tree | 153 views and 633 lines drawn as trees; incidents of 15 Nov have no group (they predate the field), so that level is skipped as designed |
| No links | 0 links on incident rows |
| History | `/v1/invoices` latency: "In the last 2 days (17)", newest first, "reason: —" |
| Found and fixed | "Last time before this" first picked the newest **other** incident, which can be newer than the one opened from the closed list; now the newest that **started before** it (test added) |
| Long values | A 127-character `api` path shown whole and wrapped: `text-overflow: clip`, `overflow-wrap: anywhere` on line, panel and view; it fits the row |

The page's test data (`__bench`) now carries a view, groups and a 127-character path. It could not be run here
because the hidden browser pane pauses animation frames, which `__bench` waits for.

Afterwards `config-repo` was reverted to `day-by: started` and the system stopped.
