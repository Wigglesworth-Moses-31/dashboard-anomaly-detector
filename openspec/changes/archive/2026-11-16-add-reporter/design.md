# Design: add-reporter

## Context

See proposal.md for the why. Facts that shape the design:

- `incident-events` (Kafka, 2 days) holds every incident change with the full incident (add-detector); the Notifier
  already rebuilds a day from it without a database (add-notifier N1). `metric-series` (compacted) holds every line's
  description, including its kind (`KIND_UNKNOWN` for lines the Collector could not classify).
- The Store answers `GetSummary(from, to)`: per line count, min, max, mean and p95 over raw points in `[from, to)`
  (add-store S4). Raw points live in the hypertable `metrics.points` (one chunk per UTC day, 2-day safety limit);
  per-minute summaries in the continuous aggregate `metrics.points_1m`, refreshed every minute over the **last 3
  hours** (start offset 3 h).
- The shared time zone `anomaly.shared.time-zone` (Asia/Kolkata) already defines "today" for the page.
- The Reporter skeleton has settings `report-time` and `output-dir`, and health on port 8105.

## Goals / Non-Goals

**Goals:** a correct, readable report per window, exactly once; a clean-up that can never lose a window's evidence,
data still arriving, or per-minute summaries; catching up after a stop.
**Non-Goals:** sending reports anywhere, charts, windows longer than a day, history beyond what Kafka and the Store keep.

## Decisions

### R1. One loop, one window at a time
```
every minute: which windows have ended (plus a 2-minute grace)?  ──► for each, oldest first:
   pending ──collect──► written ──read back──► verified ──Store.RemoveRawBefore──► cleaned
               │                                                                     │
   incidents (Kafka incident-events)                                 state saved after every step
   numbers   (Store GetSummary)                                      (reports/.state.json)
   lines     (Kafka metric-series)
```
- **Windows** start at midnight in the shared time zone and last `window` (default 24 h; 1, 2, 3, 4, 6, 8, 12 or 24 h).
  Key: `2026-11-16` for a day, `2026-11-16_06-12` otherwise; the key is also the file name.
- **Grace** (default 2 min): a window is reported 2 minutes after it ends, so the last steps (Datadog's lag is
  absorbed by the Collector; the Detector's last closes) have arrived.
- **Catch-up**: on each check, every window that ended within `catch-up` (default 2 days, what Kafka and the Store
  keep) and is not `cleaned` is handled, oldest first. The first ever start therefore reports the last day or two too.
- **On request**: `POST /reports/{date}` (e.g. `2026-11-15`) handles that day's window(s) through the same steps; if
  already done, it answers with the existing file.

### R2. Exactly once: a state machine per window
| Step | What is true | After a crash |
|---|---|---|
| pending | nothing written | collect and write again (nothing was kept) |
| written | the file exists | read it back and verify |
| verified | the file was read back complete | clean up |
| cleaned | raw points removed | nothing to do |
- The state is a small JSON file next to the reports (`reports/.state.json`): window key -> step, incident count
  (for "yesterday 29"), points removed. Written **atomically** (write a temporary file, then rename), so a crash
  leaves either the old or the new state, never half of one. The window key is the **idempotency key**.
- **The report file is written atomically too**: `2026-11-16.md.tmp`, flushed to disk, renamed. Its last line is
  `<!-- report complete: 37 incidents, sha256 <hash of everything above> -->`; *verified* means read back, last line
  present, hash equal.

### R3. Collecting the incidents (from Kafka, like the Notifier)
- Read `incident-events` from the beginning (no group, nothing committed); keep the event with the highest sequence
  per incident (hash map); select incidents whose `started_ms` is in the window.
- "Still open at the window's end": open now, or closed after the window's end.
- **Why Kafka and not the Detector's tables:** each service owns its tables (architecture); the Notifier proved a day
  rebuilds from the topic in about a second. Kafka keeps 2 days, the same horizon as catch-up.

### R4. Collecting the numbers (from the Store)
- `GetSummary(window)` streams one summary per line (226 lines): count, min, max, mean, p95.
- **Data completeness** = points received / points expected, where expected = lines seen in the window x window
  length / 10 s. Shown in the headline ("99.8% of expected points"); below 90% the headline says the data was
  incomplete (for example, the Collector was stopped).
- **Lines that moved most**: lines with at least 30 points, ranked by max / mean; top 10 with a **min-heap of size
  10**: O(n log 10) instead of sorting all lines.
- Each incident's lines: their peak and normal come from the incident itself (the Detector's view, the same as the
  page), so the report and the page always agree.

### R5. Writing the report (Template Method)
One writer with the sections in a fixed order; each section is one method, so a section can change without touching
the others:
1. Headline: window, time zone, incidents (previous window's number), longest, still open, data completeness.
2. Incidents table: start, end, duration, title (oldest first, so the day reads in order).
3. One section per incident: each line's signal and direction, peak, normal with unit and times normal (or points),
   panel; links to each dashboard from 5 minutes before the start to 5 minutes after the end.
4. Broke most often: incidents per group (hash map, sorted; groups are about 30).
5. Lines that moved most (R4).
6. Lines not classified (kind unknown), by dashboard and panel.
7. The completion line (R2).
Markdown-sensitive characters in titles (`|`, `*`, `_`, `<`) are escaped. Times are shown in the shared time zone.

### R6. The clean-up, and what the Store protects
After *verified*, the Reporter calls `StoreService.RemoveRawBefore(before = window end - keep-before-end)` (default
1 h). The Store applies its own floor: it never removes raw points newer than **3 hours ago**, because the per-minute
summaries are refreshed from raw points over the last 3 hours; removing raw points there would make the next refresh
recompute those minutes from nothing and erase their summaries. The answer says which time it actually used and how
many points it removed.
```proto
rpc RemoveRawBefore (RemoveRawRequest) returns (RemoveRawReply);
message RemoveRawRequest { int64 before_ms = 1; }
message RemoveRawReply   { int64 removed_points = 1; int64 effective_before_ms = 2; }  // min(before, now - 3 h)
```
- Store side: whole daily chunks entirely before the time are dropped (`drop_chunks`, instant); the rest is deleted in
  one-hour slices, each its own short transaction, so the Store's writing never waits long.
- A time in the future is refused (`INVALID_ARGUMENT`). The 2-day retention policy keeps running regardless.
- Example: the day ends at 00:00, the report is verified at 00:02, the Reporter asks for 23:00, the Store uses
  21:02. Raw points from 21:02 on stay (the Detector's restart needs 30 minutes; the per-minute summaries need 3 h);
  the rest of the day is gone. The database then holds about 3 hours of raw points plus the open day.

### R7. Report files stay few
After each new report, files beyond `keep-reports` (default 30) are removed, oldest first (names sort by time).

### R8. Settings (config-repo/reporter.yml)
| Setting | Default | Meaning |
|---|---|---|
| `window` | 24h | 1h, 2h, 3h, 4h, 6h, 8h, 12h or 24h; anything else is rejected |
| `grace` | 2m | Wait after a window ends before reporting it |
| `keep-before-end` | 1h | Raw points kept before the window's end (the Store keeps at least the last 3 h anyway) |
| `catch-up` | 2d | How far back missed windows are reported (at most 2 days) |
| `output-dir` | reports | Folder of the reports and `.state.json` |
| `keep-reports` | 30 | Report files kept |
| `store-address` | localhost:9102 | The Store's gRPC |
| `anomaly.shared.time-zone` | Asia/Kolkata | Shared: where midnight is, and the times in the report |
`report-time` (skeleton) is replaced by window + grace.

### R9. Health
Last report (window, file, incidents), next window and when it is due, windows waiting, last clean-up (time used,
points removed), report folder size, last error. DEGRADED when a window has waited more than an hour (something
keeps failing).

### R10. Data structures, algorithms and patterns
| Problem | Choice | Cost (n lines, i incidents, e events kept) |
|---|---|---|
| Never two reports, finish after a crash | **State machine** per window + **idempotency key** (the window) | - |
| A crash never leaves half a file or half a state | **Atomic write**: temporary file, flush, rename | - |
| Know the report is complete | **Completion line with a SHA-256** of the content, checked on read back | O(report size) |
| One final state per incident | **Hash map**, highest sequence wins | O(e) to read, O(1) per event |
| Lines that moved most | **Min-heap of size 10** | O(n log 10) |
| Groups that broke most | **Hash map** of counts, then a sort of about 30 groups | O(i + g log g) |
| Sections in a fixed order | **Template Method** | - |
| Remove a day without blocking writes | **Drop whole chunks**, then **delete in one-hour slices** | chunk: O(1); slices: short transactions |
| Never break per-minute summaries | **Floor at now - 3 h** in the Store, where the data lives | - |
| Missed windows | **Ordered catch-up**, oldest first, bounded by 2 days | O(windows) |

## Risks / Trade-offs

- [The time zone changes during a day] → windows follow the zone in use when they end; a change mid-day can make
  one window shorter or longer. Rare; the report states its exact start and end.
- [Kafka keeps 2 days] → a window older than that cannot be reported (no incidents left); catch-up stops at 2 days.
- [A report written while the Detector still holds an incident open] → the report says "still open at the window's
  end"; the next window's report does not repeat it (it started earlier).
- [Deleting rows inside a chunk is slower than dropping chunks] → slices of one hour keep each transaction short;
  at lab scale a day is about 1.7 million rows, seconds of work.
- [The first ever start writes reports for the last two days] → expected (catch-up); the state then prevents repeats.

## Migration Plan

New service code and one contract addition (baseline refreshed on purpose). `config-repo/reporter.yml` is rewritten.
The `raw-retention` setting left in `store.yml` for the Reporter is replaced by `keep-before-end` here and removed
from the Store.

## Open Questions

None that change the plan.
