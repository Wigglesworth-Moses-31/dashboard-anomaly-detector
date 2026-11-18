# Proposal: add-backfill

## Why

The user (18 Nov 2026): "whenever a new dashboard is onboarded during first time setup or via UI, we need to pull up
metrics for the last 2 days first". Today a new dashboard starts almost empty: the Collector asks only the last
10 minutes on its first poll, so the charts are blank, the Detector needs 10 minutes before it judges anything, and
the metric pages have no past anomalies to show.

## What Changes

| What | In plain words |
|---|---|
| **When** | A dashboard view the Collector has **never collected before**: every dashboard at first-time setup, and each dashboard (or newly watched saved view) added in the Settings tab. Not on a restart, not when a dashboard's JSON is edited or replaced |
| **How much** | The **last 2 days**, at **10-second detail like live** (user, 18 Nov), from Datadog, read-only |
| **First, then live** | The view's lines are filled **oldest first**, window by window (about 4 hours per Datadog call, to be measured); live polling of that view starts when its 2 days are in. Views already being collected are not slowed |
| **Within the rate limit** | Backfill calls go through the region's own lane (its rate budget and 429 wait), batched like polls (up to 40 panels per call), and only use what live polling leaves free. Example: the lab dashboard (36 questions) ≈ 12 calls; a 270-question dashboard ≈ 84 calls, a few minutes |
| **Past anomalies are found** (user, 18 Nov) | The Detector judges the 2 days in order, as it would have live. Anomalies inside them are recorded as **past ("found in backfill")**: closed with their real start and end, **no notification, no reason needed**. One still going on when the 2 days end continues as a live anomaly |
| **Warm at once** | The Detector's idea of "normal" is built from the 2 days, so live judging starts immediately instead of after 10 minutes |
| **Shown** | The Settings tab shows "pulling the last 2 days: 45%" per dashboard; the Collector's health lists views being backfilled; the dashboard page shows the 2 days as soon as they are stored |
| **Recorded** | Which views were backfilled is remembered by the Collector, so a restart does not pull them again |
| **Within retention** | The 2 days are stored like live values and kept, compressed, inside `keep-ten-days`' 10 days |

## How we will know it works

| Check | Expected |
|---|---|
| New dashboard in the Settings tab | Within minutes its charts show 2 days; health showed its progress; its live polling started after |
| First-time setup | Every dashboard backfilled once; a Collector restart backfills nothing |
| Edit or replace | Editing a dashboard's JSON does not backfill it again |
| Past anomaly | A recorded spike 30 hours ago appears on its metric page as "past · found in backfill", closed, with no notification and no reason box |
| Ongoing | An anomaly still going on at the end of the 2 days continues live |
| Rate | During a 270-question backfill, live polls of the other dashboards keep their timing; no 429 storm |
| Order | No backfilled point is dropped by the Detector as out of order |

## Capabilities

### Modified Capabilities
- `metric-collection`: a never-collected view is filled with its last 2 days first, oldest first, through its lane;
  progress shown; remembered across restarts.
- `message-contracts`: points and incidents can say they come from backfill.
- `anomaly-detection`: backfilled points are judged in order; anomalies inside them are recorded as past; normal is
  built from them.
- `dashboard-view`: past anomalies are shown as such; the Settings tab shows backfill progress.

## Out of scope

| Not in this change | Why |
|---|---|
| Filling gaps after the machine was off (catch-up) | `add-catch-up`, later |
| More than 2 days | Asked for 2 days; settable up to the retention (10 days) |
| Backfilling on every restart | Only never-collected views |

## Impact

- `app/contracts`: `MetricPoints.backfill`, `Incident.found_in_backfill` (new fields).
- `app/collector`: backfill planner (windows, oldest first), the hand-over to live polling, the remembered set, health.
- `app/detector`: judging backfilled points as past; warm-up from them.
- `app/notifier`: no notification for past anomalies; "found in backfill" on the metric page; progress in Settings.
- `config-template/collector.yml`: `backfill-new-views: 2d`, `backfill-window: 4h`; README.
