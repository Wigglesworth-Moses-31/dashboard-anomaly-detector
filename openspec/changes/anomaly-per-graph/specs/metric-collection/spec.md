## ADDED Requirements

### Requirement: A line's place names its split tag
For a panel inside a split group, the Collector SHALL send the group's split tag (the first `one_graph_per` of its
`split_config`) with the line's place, so the line's small graph is its value of that tag. Other panels SHALL send none.

#### Scenario: Split group
- **WHEN** the panel "Success Rates" is a split group with one graph per `api`
- **THEN** each of its lines is described with the split tag `api`
