# Proposal: add-reporter

## Why

The live page shows today as it happens, but nothing keeps a record: at midnight the page moves on, and Kafka and
the Store forget after 2 days. The user planned "an EOD report of the metrics in the dashboards" and asked for a
service that "creates a report of detected anomalies in md format and cleans the timeseries db for the day", with a
window of n hours, never more than a day, because the database must not fill up. The Reporter is that last piece:
one readable Markdown file per window, and only then the clean-up.

## What Changes

The Reporter goes from an empty skeleton to a working service:

| Step | In plain words |
|---|---|
| **When** | After each window ends (default: the calendar day in the shared time zone, Asia/Kolkata; a setting can make it 6 or 12 hours, never more than 24), plus on request for a chosen day |
| **Collect the incidents** | Reads `incident-events` from Kafka, like the Notifier: every incident that started in the window, in its final state. No access to the Detector's database |
| **Collect the numbers** | Asks the Store for every line's summary of the window (`GetSummary`: count, min, max, average, p95) and each incident's lines over its own time |
| **Write the report** | `reports/2026-11-16.md`: a headline, a table of incidents, one section per incident (lines, peaks against normal, dashboard links at that time), the groups that broke most, the lines that moved most, lines the system could not classify, and how complete the data was |
| **Exactly once** | A small state per window (pending → written → verified → cleaned): a restart finishes what was left, never writes a second report |
| **Then clean up** | Only after the file is written and read back: asks the Store to remove raw points of the reported window, never of a window that has not ended, and keeping the last hour before the window's end so the Detector can restart warm (setting). Per-minute summaries stay (their own 2-day limit) |
| **Catch up** | After a stop, reports the windows it missed, as far back as the data still exists (2 days) |
| **Show its state** | Health: last report, next one, windows waiting, last clean-up, report folder size |

## Example (shortened)

```markdown
# Incident report · Mon 16 Nov 2026 · Asia/Kolkata (00:00-24:00)

**37 incidents** (yesterday 29) · longest 4 min (orders) · 35 closed, 2 still open at midnight
Data: 226 lines, 99.8% of expected points

| Start | End | Duration | Incident |
|---|---|---|---|
| 17:04:00 | 17:07:00 | 3 min | /v1/invoices: latency up, errors up |
...

## /v1/invoices: latency up, errors up (17:04:00-17:07:00)
| Line | Peak | Normal | Panel |
|---|---|---|---|
| latency up | 1,569 ms | 138 ms (11.3x) | Latencies |
| errors up | 8 | 0 | Count |
[Lab: API platform at 17:04](https://app.datadoghq.com/dashboard/w2f-pqs-ghd?from_ts=...)

## Broke most often
billing 5 · orders 5 · /v1/invoices 5 · /v1/telemetry 5 ...
```

## How we will know it works (no Datadog needed)

| Check | Expected |
|---|---|
| A report from the recording | The report for 15 November lists the incidents the Detector found, with the same peaks as the page |
| Exactly once | Restarting the Reporter in the middle gives one file, and the clean-up still completes |
| Clean-up only after | If writing fails, nothing is deleted; today's points are never deleted |
| Window setting | With a 6-hour window, four reports a day; a window above 24 hours is rejected |
| Storage | The Store's raw points drop to the open window after a clean-up |

## Capabilities

### New Capabilities
- `incident-reporting`: one Markdown report per window, exactly once, followed by a clean-up of the reported raw
  points.

### Modified Capabilities
- `metric-storage`: the Store gains one question, `RemoveRawBefore(time)`, so the Reporter can trigger the clean-up
  without touching the Store's tables (an addition to the shared contract; the 2-day safety limit stays).

## Lab limits (user, 15 Nov 2026)

- A report window is never longer than 24 hours (validated).
- After each report, raw points of the reported window are removed, so the database holds about one day plus the
  open window, below the 2-day limit and far from the 1 GB alarm.
- Report files are small (tens of KB); the folder keeps the last 30 by default (setting).

## Out of scope

| Not in this change | Why |
|---|---|
| E-mail, Slack or PDF | Markdown files first; sending is a later step |
| Charts inside the report | Links open Datadog at the right time instead |
| Reports longer than a day | The lab limit |

## Impact

- `app/reporter`: scheduler, Kafka reader, Store client, report writer, state, clean-up, health, tests.
- `app/contracts`: `StoreService.RemoveRawBefore` (addition only; baseline refreshed on purpose).
- `app/store`: answers `RemoveRawBefore` (drops whole chunks, deletes the rest in one-hour slices, never touches the last 3 hours that the per-minute summaries are refreshed from, refuses a time in the future).
- `config-repo/reporter.yml`: window, time, output folder, files kept. The time zone is the shared setting.
