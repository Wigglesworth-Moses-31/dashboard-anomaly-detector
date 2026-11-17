# Spec Delta

## ADDED Requirements

### Requirement: Incidents keep each line's panel group
Each affected line of an incident SHALL carry the group of the panel it comes from, taken from the line's description.

#### Scenario: Incident on a grouped panel
- **GIVEN** a line whose view says group "API Gateway Metrics" and panel "Request Counts by Status Code"
- **WHEN** it opens an incident
- **THEN** the incident's entry for that line says group "API Gateway Metrics" and panel "Request Counts by Status Code"
