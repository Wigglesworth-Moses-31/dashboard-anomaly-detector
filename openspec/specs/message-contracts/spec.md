# message-contracts Specification

## Purpose
Defines, in one shared place, every message the services exchange, how each metric line is identified, and
the rules that let messages change over time without breaking the services that read them.

## Requirements

### Requirement: One shared definition per message
Every message exchanged between services, and every question asked of the Store, SHALL be defined once in the
shared contracts. No service SHALL define its own copy of a message.

#### Scenario: Building the services
- **WHEN** all services are built
- **THEN** every message type they send or receive comes from the shared contracts module

### Requirement: Stable identity for each metric line
Each metric line SHALL have an id computed from its source, its query and its tags. The same line SHALL get
the same id every time, whatever order its tags arrive in; lines differing in any of these SHALL get
different ids.

#### Scenario: Tags in a different order
- **GIVEN** a line with tags `api=/v1/invoices` and `status_code=500`
- **WHEN** the same line arrives with the tags listed the other way round
- **THEN** both get the same id

#### Scenario: Different status code
- **WHEN** two lines differ only in `status_code` (500 and 200)
- **THEN** they get different ids

### Requirement: Times and missing data are unambiguous
All times SHALL be milliseconds since 1 January 1970 in UTC. A step with no data SHALL be left out of the
message, never sent as zero.

#### Scenario: A gap in the data
- **WHEN** a metric has no value for the step at 14:05:10
- **THEN** the message has no point for 14:05:10, rather than a point with value 0

### Requirement: Unfinished steps are marked
A data point whose step had not ended when it was collected SHALL be marked partial.

#### Scenario: Newest step
- **WHEN** a poll at 14:05:15 returns the step 14:05:10 to 14:05:20
- **THEN** that point is marked partial

### Requirement: Messages can grow without breaking readers
A reader SHALL accept a message that has fields it does not know, and ignore them. A field SHALL never be
renamed, removed or given another meaning in a way that changes how older messages are read; the build SHALL
fail if a contract change would do so.

#### Scenario: Newer sender, older reader
- **GIVEN** a newer sender that adds a field to `MetricPoints`
- **WHEN** an older reader receives that message
- **THEN** it reads every field it knows correctly and ignores the new one

#### Scenario: Unsafe contract edit
- **WHEN** someone changes an existing field's number or type in a contract
- **THEN** the build fails and names the field

### Requirement: Messages are checked before use
The shared contracts SHALL provide a check that tells a reader whether a received message is complete
(for example: a series id is present, points are in time order). A message that fails SHALL be reported with
the reason instead of being used.

#### Scenario: Points without a series id
- **WHEN** a `MetricPoints` message arrives with no series id
- **THEN** the check rejects it and says the series id is missing

### Requirement: Incident events are self-contained and unique
Each incident event SHALL carry the incident's full current state and an event id that no other event shares,
so a reader can show the incident from that one event and can recognise a repeated delivery.

#### Scenario: Viewer joins late
- **WHEN** a viewer receives only the latest event of an incident
- **THEN** it can show the incident's state, lines involved and peaks without any earlier event

#### Scenario: Same event delivered twice
- **WHEN** the same event arrives twice
- **THEN** both copies have the same event id

### Requirement: Order is kept per metric line and per incident
Messages about the same metric line SHALL travel with that line's id as their key, and incident events with
the incident's id, so their order is kept.

#### Scenario: Two polls of the same line
- **WHEN** two `MetricPoints` messages for the same line are sent one after the other
- **THEN** they carry the same key, and a reader receives them in the order they were sent

### Requirement: The web page gets readable messages
Messages sent to the web page SHALL be JSON with the same fields and meaning as the shared contract.

#### Scenario: Incident shown in the browser
- **WHEN** the Notifier sends an incident event to the web page
- **THEN** the browser receives JSON whose field names match the contract

### Requirement: A line's panel group travels with it
The view of a line (in its description) and each affected line of an incident SHALL carry the title of the panel's
group or section, empty when the panel is in none. These SHALL be new fields, so older readers keep working.

#### Scenario: Panel in a group
- **WHEN** a line of panel "Request Counts by Status Code" in group "API Gateway Metrics" is described, and later is part of an incident
- **THEN** its view and its affected-line entry both say group "API Gateway Metrics"

#### Scenario: Older reader
- **GIVEN** a reader built before this change
- **WHEN** it reads an incident with the new field
- **THEN** it reads every field it knows and ignores the new one

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
