## ADDED Requirements

### Requirement: A formula line is named like in Datadog
A line computed from a formula SHALL be named by the formula's alias when the widget JSON gives one, else by the
formula text with the queries it uses; it SHALL NOT be named only by the metric underneath. The metric names stay in
the side panel's details. A line that shows one query as it is keeps its metric name.

#### Scenario: Formula with an alias
- **WHEN** a Success Rate panel computes `100 * (1 - query2 / query1)` from `gateway.request.count` with the alias "success %"
- **THEN** its line is named "success %", and the side panel lists `gateway.request.count` as its metric

#### Scenario: Formula without an alias
- **WHEN** the same panel has no alias
- **THEN** its line is named `100 * (1 - query2 / query1)`

### Requirement: Formula values match Datadog
For a panel whose lines come from a formula, the values shown SHALL match Datadog's own values for the same panel
and time, within the difference the page already explains (each 10-second value is that step's highest).

#### Scenario: Success rate compared
- **WHEN** the Success Rate panel is shown for the last 15 minutes next to Datadog's own view of it
- **THEN** both show the same level (for example near 99%), not near 0% with a jump to 100% at the end
