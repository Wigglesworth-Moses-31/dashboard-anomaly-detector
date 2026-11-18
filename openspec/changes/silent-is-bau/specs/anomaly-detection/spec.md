# Spec Delta

## MODIFIED Requirements

### Requirement: Business-as-usual lines need no reason
Lines whose anomaly is in the BAU list from the settings (default: signal "general" in any direction, signal "errors"
going down, and any signal going silent) SHALL be marked BAU when they join an incident, recorded with the reason
"BAU (business as usual)", and SHALL never keep an incident waiting. An incident of only BAU lines SHALL close by
itself when calm, with the reason "BAU"; one whose BAU lines are all silent SHALL close at once. The list SHALL change
without a restart, and when it loads, the lines of waiting incidents SHALL be marked again.

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

#### Scenario: Silence
- **WHEN** a latency line and a traffic line of the same service go silent
- **THEN** both are BAU and the incident closes at once with the reason "BAU"

#### Scenario: Waiting from before
- **GIVEN** an open incident from before the change whose lines are all silent
- **WHEN** the Detector loads the BAU list
- **THEN** its lines are marked BAU and it closes with the reason "BAU"
