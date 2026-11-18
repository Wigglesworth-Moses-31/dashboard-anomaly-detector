# Verification: add-backfill

18 Nov 2026. A dashboard view never collected before gets its last 2 days at 10-second detail first.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **299 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | passed | `MetricPoints.backfill`, `Incident.found_in_backfill` pass the compatibility check; baseline refreshed |
| Collector `MultiSiteTest` | 17 passed | Of `d1` (in the Store) and `d2` (new) only `d2` is backfilled: 2 windows × 3 panels, oldest first, marked, then its units are polled live; a restart with both known backfills nothing; while the Store cannot be asked, new units wait, then are backfilled |
| Detector scenarios | 67 passed | A spike in backfilled values: OPENED marked found in backfill, never CALM, CLOSED with "found in backfill" and its real start; one still going on when live values arrive is announced OPENED again (same id, mark cleared) and then waits for a reason like any other; 15 minutes of backfill warm a line so live values are judged at once |

## Live (this machine, the user's Datadog trial, read-only)

| Check | Result |
|---|---|
| Same questions as an existing dashboard | A copy with the same filters shares the existing questions (already collected): nothing to backfill — correct |
| New questions (`env:staging`) | "Backfill: 1 new views (Backfill check copy), 12 questions, last PT48H in 12 windows"; health 0% → 33% → 67% → done in about 25 s; then the 12 questions polled live |
| Window size | Datadog answered all 12 four-hour windows at 10 s with no refusal (0 "not answered") |
| Cleanup | The test dashboard removed through the Settings API (commits in `config-repo`, local) |

## Not shown live

| What | Why |
|---|---|
| Past anomalies on the metric page | Nothing is sent to the trial, so the backfill brought no values; the Detector tests cover it and the page shows "past · found in backfill" for such incidents |
| A 270-question dashboard's timing | No such dashboard here; batching is the same as for polls (40 formulas per call) |
