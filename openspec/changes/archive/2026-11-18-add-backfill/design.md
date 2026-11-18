# Design: add-backfill

## Decisions

### B1. Contracts

`MetricPoints.backfill = 4` (bool); `Incident.found_in_backfill = 12` (bool). New fields only.

### B2. Which views are new

At each reconcile the Collector asks the Store (`ListSeries`, gRPC, cached one minute) which view ids have lines.
A watched view of a lane whose id is not among them, and not already backfilled in this run, is **new**. No state
file: the Store is the record, so a restart backfills nothing that was collected. A view whose filters match nothing
is retried at most once per run.

### B3. Running a backfill (per lane, one view at a time)

| Step | How | Cost |
|---|---|---|
| Hold | The units of a new view (questions not shared with an old view) are left out of live scheduling | O(units) |
| Windows | `[now − 2d, now)` cut into `backfill-window` (default 4 h = 1,440 steps) windows, oldest first | 12 windows |
| Calls | Each window: `Batch.pack` (up to 40 formulas), through the lane's `Fetcher` (token bucket, 429 gate, bulkhead); a pause between calls while the lane's budget is above 80% | ≈ windows × batches |
| Send | Translated like a poll (`AnswerTranslator`), descriptions first, then each line's points of the window as one `MetricPoints` with `backfill = true`, straight to Kafka (not through the live Emitter, whose memory is sized for a few minutes) | O(points) |
| Release | When the last window is sent, the units join live scheduling; their first poll asks the usual 10 minutes (overlap is harmless: a step judged twice counts once) | O(units) |
| Progress | `windows done / windows` per view on health (`backfill`), read by the Settings tab | O(1) |

Settings: `backfill-new-views` (default 2d, at most 10d, 0 = off), `backfill-window` (default 4h).

### B4. The Detector

| Event | Change |
|---|---|
| A backfill value | Judged exactly as a live one (in order per line; silence by the panel query's own watermark) |
| An incident opens from a backfill value | `foundInBackfill = true`; OPENED is still sent (stored, shown), the Notifier does not notify for it |
| It calms | Closed at once with reason "found in backfill" (no CALM, no reason asked) |
| A live value joins an incident still marked | Mark cleared; announced as OPENED again (same id): it is happening now |

### B5. The Notifier and page

No notification for `found_in_backfill` incidents; metric page "past · found in backfill"; grey shading; the Settings
tab shows "pulling the last 2 days: n%" from the Collector's health.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Datadog answers fewer points than asked for a long window | The window is a setting; the build measures the largest window answered in full at 10 s |
| Many past incidents at once | They are closed and silent; retention removes them with the rest |
