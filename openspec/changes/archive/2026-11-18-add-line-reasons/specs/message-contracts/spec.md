# Spec Delta

## ADDED Requirements

### Requirement: Each line of an incident carries its own reason
Each affected line of an incident SHALL carry its own reason, when it was given, and whether it is business as usual
(BAU). A close command SHALL be able to name one line, or none meaning every line still needing a reason. These SHALL
be new fields, so older readers keep working.

#### Scenario: One line explained
- **WHEN** a reason is given for the line `status_code=404` of an incident
- **THEN** that line's entry carries the reason and its time, and the other lines carry none

#### Scenario: A BAU line
- **WHEN** a "general" line joins an incident
- **THEN** its entry says BAU and its reason reads "BAU (business as usual)"
