# Spec Delta

## ADDED Requirements

### Requirement: Many lines' history on request
The Store SHALL answer one history question for many metric lines (up to 1000), one time range and one resolution,
oldest first per line, in chunks of at most 1000 points, each naming its line. Unknown lines and lines with no data
SHALL get no chunk; a question for more than 1000 lines SHALL be refused.

#### Scenario: Whole dashboard
- **WHEN** a client asks for 200 lines over the last 3 hours at raw resolution
- **THEN** it receives every point of those lines in that range, each chunk naming its line, within 1 second

#### Scenario: Too many lines
- **WHEN** a client asks for 1500 lines
- **THEN** the question is refused with a message saying the limit is 1000

### Requirement: Every known line on request
The Store SHALL answer the list of every metric line it keeps, each with its full description, newest description
per line.

#### Scenario: Catalogue
- **WHEN** a client asks for every line while 452 lines are kept
- **THEN** it receives 452 descriptions
