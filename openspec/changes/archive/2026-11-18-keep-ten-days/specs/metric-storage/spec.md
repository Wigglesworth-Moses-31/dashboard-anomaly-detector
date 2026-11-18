# Spec Delta

## MODIFIED Requirements

### Requirement: Storage stays small
Raw points and per-minute summaries SHALL each be kept for the configured time (default 10 days, at most 10) and
removed by age, whole time slices at once. Data older than a few hours SHALL be stored compressed. This is a lab
system: no setting may keep data longer than 10 days.

#### Scenario: The Reporter did not run for three days
- **WHEN** no report has been written for 3 days
- **THEN** raw points and per-minute summaries of those 3 days are all still there, removed only once 10 days old

#### Scenario: Ten days later
- **WHEN** the system has run for 11 days
- **THEN** raw points and per-minute summaries of the last 10 days are there and older ones are gone

#### Scenario: A report does not remove data
- **WHEN** the daily report has been written
- **THEN** that day's raw points are still stored

#### Scenario: Asking to keep data for a month
- **WHEN** an operator sets the raw retention to 30 days
- **THEN** the setting is rejected and the previous retention stays in use

## ADDED Requirements

### Requirement: Older data is compressed
Raw points and per-minute summaries older than the configured age (default a few hours) SHALL be compressed by line
and time. Questions SHALL get the same answers from compressed data as before it was compressed. The size before and
after compression SHALL be visible on the Store's health.

#### Scenario: Same answer compressed
- **WHEN** a line's 7-day history is asked for before and after its older data is compressed
- **THEN** both answers are identical

#### Scenario: Ten days fit
- **WHEN** this machine's lines have been stored for 10 days
- **THEN** the database stays under the 1 GB size alarm, and the health shows the compressed size per table
