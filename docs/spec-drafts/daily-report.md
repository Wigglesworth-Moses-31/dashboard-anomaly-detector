# Spec Delta

## Purpose

Produces one readable Markdown summary of each day's incidents and metric health, and only then clears old
raw data, so storage stays small without ever losing a day's evidence.

## ADDED Requirements

### Requirement: One Markdown report per day
At the configured time (default 23:30 India Standard Time) the system SHALL write a Markdown file named
after the date (for example `2026-11-15.md`). It SHALL start with a short headline (number of incidents,
overall success rate compared with the day before), then list each incident with its time, duration,
metric lines, peaks and dashboard links.

#### Scenario: Day with incidents
- **WHEN** three incidents happened today
- **THEN** today's report opens with a headline naming 3 incidents, followed by one section per incident

#### Scenario: Quiet day
- **WHEN** no incident happened today
- **THEN** a report is still written, saying there were no incidents

### Requirement: Exactly one report per day
Running the report job again for the same day SHALL NOT produce a second report or a duplicate file; it SHALL
finish any step that did not complete before.

#### Scenario: Restart during the report
- **WHEN** the Reporter restarts after writing the report but before cleaning up
- **THEN** after the restart the report is not written again, and the cleanup completes

### Requirement: Clean up only after the report is saved
The system SHALL delete raw data points only after the day's report has been saved and verified. It SHALL
keep small summaries and full-detail copies of each incident's time window, for the retention set in
configuration.

#### Scenario: Report fails
- **WHEN** writing the report fails
- **THEN** no data is deleted, and the job tries again later

#### Scenario: Incident evidence kept
- **WHEN** cleanup runs after a day with an incident from 14:05 to 14:08
- **THEN** the full-detail data around 14:05 to 14:08 is still available afterwards

### Requirement: Today's data is never deleted
Cleanup SHALL only remove raw data from days that have ended, so data still arriving is never lost.

#### Scenario: Points arriving during cleanup
- **WHEN** cleanup runs at 23:35 while new points for today are still arriving
- **THEN** none of today's points are deleted
