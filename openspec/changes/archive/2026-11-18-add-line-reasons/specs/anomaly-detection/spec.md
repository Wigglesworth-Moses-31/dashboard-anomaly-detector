# Spec Delta

## MODIFIED Requirements

### Requirement: Only a reason closes an incident
Each line of an incident that is not BAU SHALL need its own reason (1 to 500 characters); a reason may be given for
one line or for every line still needing one, whether the incident is open or calm. The reason and the time it was
given SHALL be saved with the line and announced, together. The incident SHALL close when it is calm and every line
that is not BAU has a reason, or at once when one reason is given for every waiting line of an open incident (for
example lines whose data stopped, which never become calm); its own reason SHALL then summarise them (the one reason when all are the same, else
"n reasons"). A later spike of the same group SHALL open a new incident. A reasoned line that spikes again while the
incident is open SHALL need a new reason. An empty or too long reason, or a reason for an incident or line that is
unknown or already closed, SHALL change nothing.

#### Scenario: Reason for a calm incident
- **WHEN** the reason "certificate renewal on the gateway" is given for every line of a calm incident
- **THEN** it is closed, the reason is stored and announced on its lines, and the next spike of its group opens a new incident

#### Scenario: Reason for an open incident
- **WHEN** the reason "test environment switched off" is given for every line of an incident open for two days
- **THEN** its lines carry the reason and it closes at once

#### Scenario: Empty reason
- **WHEN** an empty reason is given
- **THEN** nothing changes

#### Scenario: One line of several
- **GIVEN** a calm incident with the lines `status_code=404` (errors up) and `status_code=500` (errors up)
- **WHEN** a reason is given for the 404 line only
- **THEN** the incident stays calm, waiting for the 500 line's reason

#### Scenario: The line spikes again
- **GIVEN** an open incident whose 404 line already has a reason
- **WHEN** that line calms and spikes again before the incident is calm
- **THEN** its reason is cleared and it needs a new one

## ADDED Requirements

### Requirement: Business-as-usual lines need no reason
Lines whose anomaly is in the BAU list from the settings (default: signal "general" in any direction including
silence, and signal "errors" going down) SHALL be marked BAU when they join an incident, recorded with the reason
"BAU (business as usual)", and SHALL never keep an incident waiting. An incident made only of BAU lines SHALL close by
itself when calm, with the reason "BAU". The list SHALL change without a restart.

#### Scenario: Mixed incident
- **GIVEN** an incident with 404 errors up, three partners silent and two partners "general up"
- **WHEN** it is calm and the 404 line has a reason
- **THEN** it closes; the five other lines read "BAU (business as usual)"

#### Scenario: Only BAU
- **WHEN** an incident of only "general up" lines becomes calm
- **THEN** it closes by itself with the reason "BAU"

#### Scenario: Errors down
- **WHEN** an error count line is judged down
- **THEN** it is BAU

#### Scenario: Setting changed
- **WHEN** "general" is removed from the BAU list and committed
- **THEN** new "general" lines need a reason, without a restart
