# Spec Delta

## ADDED Requirements

### Requirement: Each line says its panel's group
When describing a line, the Collector SHALL fill the group of each panel showing it: the dashboard group the panel is
in, or the section heading above it, empty when neither.

#### Scenario: Panel below a section heading
- **GIVEN** a top-level panel "Latencies" below the empty group "Upstream Service Metrics"
- **WHEN** its lines are described
- **THEN** their view says group "Upstream Service Metrics"
