# Spec Delta

## ADDED Requirements

### Requirement: Incidents carry their state of calm and their reason
An incident SHALL be open, calm (all lines normal again, waiting for a reason) or closed. A closed incident SHALL
carry the reason given and when it was given; an incident closed before reasons existed has an empty reason. These
SHALL be new fields and a new state value, so older readers keep working.

#### Scenario: Closed with a reason
- **WHEN** an incident is closed with the reason "deploy of payments v2.3 rolled back"
- **THEN** its closing event carries that reason and the time it was given

#### Scenario: Calm
- **WHEN** an incident's lines have been normal for the calm period and no reason was given
- **THEN** its event says the incident is calm, with the time it became calm

### Requirement: Closing an incident is a command
Closing an incident SHALL be asked for with a command on its own topic, naming the incident, the reason and a unique
command id, so the service that owns incidents applies it and repeats of the same command change nothing.

#### Scenario: Repeated command
- **WHEN** the same close command arrives twice
- **THEN** the incident is closed once, with one closing event
