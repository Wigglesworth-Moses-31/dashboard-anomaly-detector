# Spec Delta

## MODIFIED Requirements

### Requirement: The unfinished step is marked, then completed
A step SHALL be sent marked partial until it ended at least the configured settle delay (default 60 seconds) before
the answer arrived, because the source can still be receiving its values. After that it SHALL be sent again
unmarked. A settle delay of 0 SHALL keep the old behaviour (partial only while the step's window has not ended).

#### Scenario: Step still filling
- **WHEN** a poll at 14:05:15 returns the step 14:05:10 to 14:05:20
- **THEN** that point is sent marked partial
- **AND** a later poll sends the same step unmarked

#### Scenario: Step ended but still settling
- **GIVEN** a settle delay of 60 seconds
- **WHEN** a poll at 14:05:50 returns the step 14:05:10 to 14:05:20 with a count of 1
- **THEN** that point is sent marked partial
- **AND** a poll at 14:06:25 returning the complete count of 13 sends it unmarked with 13

## ADDED Requirements

### Requirement: Late values are measured
When a poll returns a different value for a step that was already sent unmarked, the Collector SHALL count it per
source and remember how long after the step's end the change arrived; the Collector's health SHALL show the count
and the latest such delay, so the settle delay can be chosen from facts.

#### Scenario: A count completed after the settle delay
- **GIVEN** a settle delay of 30 seconds and a step sent unmarked with a count of 9
- **WHEN** a poll 50 seconds after the step ended returns 13 for it
- **THEN** the step is sent again with 13, and health shows one late change for that source, 50 seconds after the step
