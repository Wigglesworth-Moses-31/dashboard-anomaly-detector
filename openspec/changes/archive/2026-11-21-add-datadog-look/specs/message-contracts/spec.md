# Spec Delta

## ADDED Requirements

### Requirement: A line's view names its widget
Each view of a metric line SHALL carry the id of its widget in the exported dashboard JSON (0 when not known), as a
new field, so a page can place every line in exactly its widget even when two widgets share a title.

#### Scenario: Two widgets with the same title
- **GIVEN** a dashboard with two "Latency" widgets, ids 101 and 202
- **WHEN** their lines are described
- **THEN** each line's view says 101 or 202

### Requirement: Many lines' history at a chosen interval
The Store's batch history question SHALL be able to ask for values rolled up to an interval with a method (average,
sum, minimum or maximum), so a page receives at most a few hundred points per line, computed in the database. New
fields only; a question without them is answered as before.

#### Scenario: Six hours at two minutes
- **WHEN** 200 lines are asked for 6 hours at a 2-minute interval, averaged
- **THEN** each line comes back with at most 180 points, each the average of its interval
