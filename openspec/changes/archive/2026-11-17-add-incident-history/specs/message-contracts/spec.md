# Spec Delta

## ADDED Requirements

### Requirement: A line's panel group travels with it
The view of a line (in its description) and each affected line of an incident SHALL carry the title of the panel's
group or section, empty when the panel is in none. These SHALL be new fields, so older readers keep working.

#### Scenario: Panel in a group
- **WHEN** a line of panel "Request Counts by Status Code" in group "API Gateway Metrics" is described, and later is part of an incident
- **THEN** its view and its affected-line entry both say group "API Gateway Metrics"

#### Scenario: Older reader
- **GIVEN** a reader built before this change
- **WHEN** it reads an incident with the new field
- **THEN** it reads every field it knows and ignores the new one
