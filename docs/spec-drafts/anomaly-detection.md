# Spec Delta

## Purpose

Spots unusual jumps in the collected metrics within seconds of the data arriving, and turns spikes that
belong to the same problem into a single incident, so people get one clear alert per problem.

## ADDED Requirements

### Requirement: Spot a spike quickly
With default settings, the system SHALL raise an incident within 30 seconds after the data showing the
spike becomes available from the data source.

#### Scenario: Error count jumps
- **GIVEN** a metric line that normally shows about 5 errors per step
- **WHEN** it shows 60 errors per step, and that data becomes available from the source at 14:05:20
- **THEN** an incident for that line is open by 14:05:50

### Requirement: Detection rules come from configuration
What counts as a spike (for example "3 times the normal value for 2 steps in a row") SHALL be set in
configuration, per kind of metric (count, rate, gauge, latency, ratio).

#### Scenario: Stricter latency rule
- **WHEN** an operator changes the latency rule from 2 steps in a row to 3 steps in a row
- **THEN** a latency jump lasting only 2 steps no longer opens an incident

### Requirement: One incident per problem
Spikes on different metric lines that overlap in time and belong to the same dashboard or service SHALL be
grouped into one incident. An incident SHALL record its start, its end, the metric lines involved and the
peak value of each.

#### Scenario: Ten panels spike together
- **WHEN** ten metric lines from the same service spike within the same minute
- **THEN** one incident is opened that lists all ten lines, not ten incidents

### Requirement: Incidents close only after a calm period
An incident SHALL close only after all its metric lines have been back to normal for a configured time
(default 2 minutes), so a line that briefly dips and rises again does not open a new incident each time.

#### Scenario: Flapping metric
- **WHEN** a metric line goes back to normal for 30 seconds and then spikes again
- **THEN** the same incident stays open, and no second incident is created

### Requirement: Unfinished steps do not cause false alarms
The system SHALL NOT treat a partial (still-filling) step as a drop.

#### Scenario: Low value in a partial step
- **WHEN** the newest step is marked partial and shows half the normal value
- **THEN** no incident is opened because of it

### Requirement: Careful with unknown metrics
For a metric whose kind is not known, the system SHALL apply only a cautious general rule, and the daily
report SHALL list such metrics so their kind can be set in configuration.

#### Scenario: New metric with no kind
- **WHEN** a dashboard gains a metric that no setting describes
- **THEN** it is watched with the cautious rule
- **AND** it appears in the next daily report under unknown metrics
