# Spec Delta

## MODIFIED Requirements

### Requirement: A line's past is one click away
Opening an affected line SHALL show the line's earlier incidents of the last 7 days: the last one before the current
one (start, duration, peak against normal, reason) and the list of all of them, newest first. A reason not given yet
SHALL show as "—". The history SHALL come back the same after a Notifier restart, and SHALL keep at most a fixed
number of entries per line (oldest dropped first).

#### Scenario: Three times in two days
- **GIVEN** a line had incidents 5 days ago at 22:14 and today at 09:03
- **WHEN** its incident of 13:04 is opened on the page
- **THEN** it shows "last time: today 09:03" with its duration, peak and normal, and lists 3 incidents in the last 7 days

#### Scenario: After a restart
- **WHEN** the Notifier restarts and the same line is opened
- **THEN** the same history is shown

#### Scenario: Older than two days
- **WHEN** a line's only earlier incident was 8 days ago
- **THEN** the page shows "no earlier incident in the last 7 days"
