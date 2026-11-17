# Spec Delta

## MODIFIED Requirements

### Requirement: Incidents close only after a calm period
An incident SHALL become calm, not closed, once all its lines have been back to normal for a set time (default 2
minutes); its end time SHALL be the first calm step. A line that spikes again before that SHALL keep the same incident
open, and a spike of its group while it is calm SHALL make it open again, the same incident.

#### Scenario: Flapping line
- **WHEN** a line goes back to normal for 30 seconds and then spikes again
- **THEN** the same incident stays open and no second incident is opened

#### Scenario: Lines of one problem read out of order
- **WHEN** an incident becomes calm on one line's values up to 08:27:20, and then another line of the same group
  reports a spike that started at 08:25:20
- **THEN** the incident is open again with that line, and no second incident is opened

#### Scenario: Problem over
- **WHEN** all lines of an incident are normal for 2 minutes
- **THEN** the incident becomes calm with its end time set to the first calm step, and is not closed

#### Scenario: Calm, then it happens again
- **GIVEN** an incident that became calm an hour ago and has no reason yet
- **WHEN** a line of its group spikes again
- **THEN** the same incident is open again; no new incident is opened

### Requirement: Storage stays small
Closed incidents SHALL be removed after a set time (default 2 days, at most 7), with their reasons. Open and calm
incidents SHALL never be removed. Each line's history SHALL use a fixed amount of memory.

#### Scenario: Old incidents
- **WHEN** an incident closed 3 days ago
- **THEN** it is no longer stored

#### Scenario: Waiting for a reason
- **WHEN** an incident has been calm for 3 days without a reason
- **THEN** it is still stored

## ADDED Requirements

### Requirement: Only a reason closes an incident
An incident SHALL close only when a reason of 1 to 500 characters is given for it, whether it is open or calm. The
reason and the time it was given SHALL be saved with the incident and announced in its closing event, together. A
later spike of the same group SHALL open a new incident. An empty or too long reason, or a reason for an incident that
is unknown or already closed, SHALL change nothing.

#### Scenario: Reason for a calm incident
- **WHEN** the reason "certificate renewal on the gateway" is given for a calm incident
- **THEN** it is closed, its reason is stored and announced, and the next spike of its group opens a new incident

#### Scenario: Reason for an open incident
- **WHEN** the reason "test environment switched off" is given for an incident open for two days
- **THEN** it is closed at once

#### Scenario: Empty reason
- **WHEN** an empty reason is given
- **THEN** the incident is not closed

### Requirement: Waiting incidents stay visible
Open and calm incidents SHALL be announced again at least every 12 hours, so readers that rebuild from the last 2 days
of events still know them.

#### Scenario: Calm for three days
- **GIVEN** an incident calm for 3 days without a reason
- **WHEN** the Notifier is restarted
- **THEN** the incident is on the page under "Needs a reason"
