## ADDED Requirements

### Requirement: The Store names each line's newest time
The Store's gRPC service SHALL have a call that streams, per line, its id and the UTC milliseconds of its newest saved
value. Adding it SHALL keep every earlier call and message compatible.

#### Scenario: Compatible
- **WHEN** the contract check runs against the saved baseline
- **THEN** it passes, with the new call and messages reported as additions
