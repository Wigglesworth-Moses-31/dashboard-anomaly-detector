# Spec Delta

## REMOVED Requirements

### Requirement: Storage stays small
**Reason**: Replaced by "Metric values are kept 24 hours" (user, 21 Nov 2026: hold only the last 24 hours).
**Migration**: Set `raw-safety-limit` and `summary-retention` to 1d in `store.yml`.

## ADDED Requirements

### Requirement: Metric values are kept 24 hours
Raw points and per-minute summaries SHALL each be kept for the configured time (default 24 hours, at most 10 days)
and removed by age, whole time slices at once; a report SHALL remove nothing. This is a lab system: no setting may
keep data longer than 10 days.

#### Scenario: A day later
- **WHEN** the system has run for 2 days
- **THEN** raw points and per-minute summaries of the last 24 hours are there and older ones are gone

#### Scenario: Asking to keep data for a month
- **WHEN** an operator sets the raw retention to 30 days
- **THEN** the setting is rejected and the previous retention stays in use
