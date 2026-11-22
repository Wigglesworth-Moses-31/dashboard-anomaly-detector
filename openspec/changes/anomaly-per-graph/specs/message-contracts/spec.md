## ADDED Requirements

### Requirement: A view names its split tag
The `View` message SHALL have a `split_tag` field, empty for panels that are not split groups. Adding it SHALL keep
every earlier message compatible.

#### Scenario: Compatible
- **WHEN** the contract check runs against the saved baseline
- **THEN** it passes, with `View.split_tag` reported as an addition
