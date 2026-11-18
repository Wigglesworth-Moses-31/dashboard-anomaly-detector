# Spec Delta

## ADDED Requirements

### Requirement: A never-collected view is filled with its last 2 days first
A watched dashboard view with no line in the Store yet SHALL first get its last 2 days (a setting, at most the
retention) from Datadog at the live step, window by window, oldest first, sent marked as backfill; its live polling
SHALL start after them. A view already collected SHALL NOT be backfilled again, after a restart or an edit either.

#### Scenario: Dashboard added in the Settings tab
- **WHEN** a new dashboard is added for US
- **THEN** its last 2 days are sent oldest first, marked as backfill, and its live polling starts after them

#### Scenario: Restart
- **WHEN** the Collector restarts with dashboards it has collected before
- **THEN** nothing is backfilled

### Requirement: Backfill never starves live polling
Backfill calls SHALL go through the region's lane (its rate budget, 429 wait and calls in flight), batched like
polls, and SHALL use only capacity live polling leaves free, so other views keep their polling timing.

#### Scenario: Large dashboard
- **WHEN** a 270-question dashboard is backfilled
- **THEN** the other dashboards' polls keep their interval and no wave of 429 answers follows

### Requirement: Backfill progress is visible
The Collector's health SHALL list each view being backfilled with its progress (windows done of windows), and the
Settings tab SHALL show it per dashboard.

#### Scenario: Progress
- **WHEN** 6 of 12 windows of a view are done
- **THEN** health shows that view at 50%, and the Settings tab "pulling the last 2 days: 50%"
