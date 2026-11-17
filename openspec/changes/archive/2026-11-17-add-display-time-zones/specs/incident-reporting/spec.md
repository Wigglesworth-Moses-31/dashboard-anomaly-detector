# Spec Delta

## ADDED Requirements

### Requirement: Report times in all display zones
Every time in a report SHALL appear in each display zone, in the same form as on the page.

#### Scenario: An incident in a report
- **WHEN** a report lists an incident that started at 13:04:10 UTC on 17 Nov 2026
- **THEN** its start reads `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`
