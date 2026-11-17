# Spec Delta

## ADDED Requirements

### Requirement: Where each panel sits is recorded
When the Collector reads a dashboard, it SHALL record for each panel the title of its group (empty when none), the
group's position on the dashboard and the panel's position in the group, counting groups and panels in the
dashboard's own order, including split groups. A changed order SHALL be sent again after the next re-read.

#### Scenario: Panels in groups
- **GIVEN** a dashboard with a panel outside any group, then the group "Upstream" with two panels
- **WHEN** the Collector reads it
- **THEN** the lone panel and the group get positions 0 and 1, and the group's panels positions 0 and 1 in it

#### Scenario: Panel moved in Datadog
- **WHEN** someone moves a panel to another group on a watched dashboard
- **THEN** after the next re-read its lines are described again with the new group and positions
