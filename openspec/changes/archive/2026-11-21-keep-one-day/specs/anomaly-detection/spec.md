# Spec Delta

## MODIFIED Requirements

### Requirement: Storage stays small
Closed incidents SHALL be removed after a set time (default 24 hours, at most 10 days), with their reasons. Open and
calm incidents SHALL never be removed. Each line's history SHALL use a fixed amount of memory.

#### Scenario: Old incidents
- **WHEN** an incident closed 25 hours ago
- **THEN** it is no longer stored

#### Scenario: Six days old
- **WHEN** an incident closed 20 hours ago
- **THEN** it is still stored, with its reasons

#### Scenario: Waiting for a reason
- **WHEN** an incident has been calm for 3 days without a reason
- **THEN** it is still stored
