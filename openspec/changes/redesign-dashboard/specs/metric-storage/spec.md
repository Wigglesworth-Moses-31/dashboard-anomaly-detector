## ADDED Requirements

### Requirement: Newest saved time of each line
The Store SHALL answer, for every line with values in the kept time, the time of its newest saved value.

#### Scenario: After a stop
- **WHEN** values of a line were last saved at 23:00 and the Collector asks at 08:00
- **THEN** the answer gives that line 23:00
