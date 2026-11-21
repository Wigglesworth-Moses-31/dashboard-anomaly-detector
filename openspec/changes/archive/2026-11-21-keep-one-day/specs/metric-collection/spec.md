# Spec Delta

## REMOVED Requirements

### Requirement: A never-collected view is filled with its last 2 days first
**Reason**: Replaced by "A never-collected view is filled with its last 24 hours first" (user, 21 Nov 2026).
**Migration**: `backfill-new-views` defaults to 1d; a `collector.yml` that sets it keeps its value.

## ADDED Requirements

### Requirement: A never-collected view is filled with its last 24 hours first
A watched dashboard view with no line in the Store yet SHALL first get its last 24 hours (a setting, at most the
retention) from Datadog at the live step, window by window, oldest first, sent marked as backfill; its live polling
SHALL start after them. A view already collected SHALL NOT be backfilled again, after a restart or an edit either.

#### Scenario: Dashboard added in the Settings tab
- **WHEN** a new dashboard is added for US
- **THEN** its last 24 hours are sent in 6 windows oldest first, marked as backfill, and its live polling starts after them

#### Scenario: Restart
- **WHEN** the Collector restarts with dashboards it has collected before
- **THEN** nothing is backfilled
