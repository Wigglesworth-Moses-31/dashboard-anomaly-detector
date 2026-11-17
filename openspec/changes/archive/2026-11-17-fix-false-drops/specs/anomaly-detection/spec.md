# Spec Delta

## ADDED Requirements

### Requirement: Error counts are recognised by a status tag too
The default rules SHALL treat a count line tagged `status` with a 4xx or 5xx value like one tagged `status_code`: it
is an error line, judged upwards only, so fewer errors never open an incident.

#### Scenario: Fewer 404s
- **GIVEN** a count line tagged `status=404` that is normally 13
- **WHEN** it drops to 0
- **THEN** no incident is opened

#### Scenario: More 500s
- **GIVEN** a count line tagged `status=500` that is normally 2
- **WHEN** it rises to 40 for the steps the rule requires
- **THEN** an incident for "errors up" is opened
