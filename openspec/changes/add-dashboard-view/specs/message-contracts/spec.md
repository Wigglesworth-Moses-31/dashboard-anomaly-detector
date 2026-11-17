# Spec Delta

## ADDED Requirements

### Requirement: A line's description says where it sits on the dashboard
Each panel a metric line appears on SHALL carry, besides the dashboard and panel titles, the title of the group the
panel is in (empty when it is in none), the group's position on the dashboard and the panel's position in it. These
SHALL be new fields, so older readers keep working.

#### Scenario: Panel inside a group
- **GIVEN** the panel "Latency" is the second panel of the group "Upstream", the first group on its dashboard
- **WHEN** a line of that panel is described
- **THEN** its view says group "Upstream", group position 0, panel position 1

#### Scenario: Older reader
- **GIVEN** a Detector built before this change
- **WHEN** it reads a line description that has the new fields
- **THEN** it reads every field it knows correctly and ignores the new ones

### Requirement: Past values of many lines in one question
The Store's question set SHALL include one question for the past values of many lines over one time range and one
resolution, answered as a stream in which each chunk names its line.

#### Scenario: Asking for a whole dashboard
- **WHEN** a client asks for 200 lines over the last 3 hours at raw resolution
- **THEN** it sends one question and receives chunks, each naming the line its points belong to
