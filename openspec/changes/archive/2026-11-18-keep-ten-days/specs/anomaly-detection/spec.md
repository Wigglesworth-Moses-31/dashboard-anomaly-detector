# Spec Delta

## MODIFIED Requirements

### Requirement: Storage stays small
Closed incidents SHALL be removed after a set time (default 10 days, at most 10), with their reasons. Open and calm
incidents SHALL never be removed. Each line's history SHALL use a fixed amount of memory.

#### Scenario: Old incidents
- **WHEN** an incident closed 11 days ago
- **THEN** it is no longer stored

#### Scenario: Six days old
- **WHEN** an incident closed 9 days ago
- **THEN** it is still stored, with its reasons

#### Scenario: Waiting for a reason
- **WHEN** an incident has been calm for 11 days without a reason
- **THEN** it is still stored

### Requirement: Waiting incidents stay visible
Open and calm incidents SHALL be announced again at least every 12 hours, so readers that rebuild from the last 10 days
of events still know them.

#### Scenario: Calm for three days
- **GIVEN** an incident calm for 3 days without a reason (and the same after 11 days)
- **WHEN** the Notifier is restarted
- **THEN** the incident is on the page under "Needs a reason"
