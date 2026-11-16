# Spec Delta

## Purpose

Finds every metric shown on the chosen dashboards and keeps their newest numbers flowing into the system,
in small time steps that keep short spikes visible, without overloading the data source.

## ADDED Requirements

### Requirement: Find every panel on the watched dashboards
The system SHALL read the definition of each watched dashboard and find every metric query on it, including
queries inside grouped and split panels. It SHALL read the definitions again on a configured schedule
(default: every 60 minutes) to pick up added, changed or removed panels.

#### Scenario: Panel added to a dashboard
- **WHEN** someone adds a panel to a watched dashboard
- **THEN** its metrics are collected after the next scheduled re-read, with no change to the system

#### Scenario: Panel removed
- **WHEN** a panel is removed from a watched dashboard
- **THEN** its metrics stop being collected after the next re-read, and their stored history is kept

### Requirement: Collect recent data in small steps that keep peaks
The system SHALL fetch each metric in steps of a configured length (default 10 seconds), using the highest
value inside each step, on a configured interval (default every 15 seconds). On start it SHALL fetch the
last 10 minutes; afterwards only the most recent minutes.

#### Scenario: A 20-second spike
- **GIVEN** default settings
- **WHEN** a metric jumps for 20 seconds and then returns to normal
- **THEN** at least one collected step carries the peak value

#### Scenario: Collector start
- **WHEN** the Collector starts
- **THEN** each watched metric has its last 10 minutes of data collected

### Requirement: Mark the newest, still-filling step
The system SHALL mark a step whose time window has not ended yet as partial, and SHALL send its complete
value later.

#### Scenario: Step still filling
- **WHEN** a poll returns a step that ends after the time of the poll
- **THEN** that step is marked partial
- **AND** a later poll delivers the same step unmarked

### Requirement: Stay within the source's rate limits
The system SHALL stay within the request limits the data source reports. When the source answers that the
limit is reached, the system SHALL wait until the source says it may continue, then resume all watched
metrics without dropping any of them.

#### Scenario: Throttled by the source
- **WHEN** the data source answers "too many requests"
- **THEN** the Collector pauses until the reported reset time
- **AND** afterwards every watched metric is collected again

### Requirement: Each data point is stored once
Polls overlap in time, so the same data point can be fetched more than once. The system SHALL store each
point (one metric line at one time) once, with its latest value.

#### Scenario: Overlapping polls
- **WHEN** two polls both return the step at 14:05:10 for the same metric line
- **THEN** the stored history holds one value for 14:05:10

### Requirement: Read-only toward the data source
The system SHALL only read from the data source. It SHALL NOT create, change or delete anything there.

#### Scenario: Normal operation
- **WHEN** the system runs for a full day
- **THEN** no dashboard, monitor or setting in the data source has been created or changed by it
