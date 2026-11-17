# Spec Delta

## ADDED Requirements

### Requirement: Drops read as percentages
For a line below normal, the page SHALL show how far it fell as a percentage of normal ("−92%"), and a value of 0
as "dropped to 0 (−100%)", never a ratio divided by zero.

#### Scenario: Drop to zero
- **WHEN** an incident line has value 0 and normal 5
- **THEN** the page shows "dropped to 0 (−100%)"

#### Scenario: Partial drop
- **WHEN** an incident line has value 1 and normal 13
- **THEN** the page shows "−92%"
