# Spec Delta

## Purpose

Finds every metric shown on the watched dashboards and keeps their newest numbers flowing into the system,
in small time steps that keep short spikes visible, without overloading the data source.

## ADDED Requirements

### Requirement: Find every panel on the watched dashboards
The system SHALL read each watched dashboard's definition and find every metric question on it, including
inside groups and split groups, tables and other charts. Panels with no metric question SHALL be skipped. The
definitions SHALL be read again on the configured schedule (default every 60 minutes).

#### Scenario: Panels inside groups
- **WHEN** a watched dashboard holds timeseries panels inside a group and a split group
- **THEN** every metric question of those panels is collected

#### Scenario: Panel added to a dashboard
- **WHEN** someone adds a panel to a watched dashboard
- **THEN** its numbers are collected after the next scheduled re-read, with no change to the system

#### Scenario: Panel removed
- **WHEN** a panel is removed from a watched dashboard
- **THEN** it stops being collected after the next re-read

### Requirement: Dashboard filters are filled in
Placeholders in a dashboard's questions (such as `$env`) SHALL be filled from the settings for that dashboard,
or else from the dashboard's own default. A placeholder whose value is "all" (`*`) SHALL be dropped from the
filter.

#### Scenario: Value from settings
- **GIVEN** settings say `env` is `prod` for the dashboard
- **WHEN** a panel asks for `sum:requests{$env,$team}` and `team` defaults to `*`
- **THEN** the Collector asks Datadog for `sum:requests{env:prod}`

### Requirement: Collect recent data in small steps that keep peaks
The system SHALL fetch each panel's numbers in steps of the configured length (default 10 seconds), on the
configured interval (default every 15 seconds). Each step SHALL hold the highest value inside it, except
counts, which SHALL hold the total for the step. On start it SHALL fetch the configured backfill (default 10
minutes); afterwards only the recent window (default 2 minutes).

#### Scenario: A 20-second spike
- **GIVEN** default settings
- **WHEN** a metric jumps for 20 seconds and then returns to normal
- **THEN** at least one collected step carries the peak value

#### Scenario: Collector start
- **WHEN** the Collector starts
- **THEN** each watched metric line has its last 10 minutes collected

### Requirement: Gaps are left out
A step for which the source has no value SHALL produce no point. It SHALL NOT be sent as zero.

#### Scenario: Missing step
- **WHEN** the source returns no value for the step at 14:05:10
- **THEN** the message has no point for 14:05:10

### Requirement: The unfinished step is marked, then completed
A step whose time window had not ended when collected SHALL be sent marked partial. Once it has ended, it
SHALL be sent again unmarked.

#### Scenario: Step still filling
- **WHEN** a poll at 14:05:15 returns the step 14:05:10 to 14:05:20
- **THEN** that point is sent marked partial
- **AND** a later poll sends the same step unmarked

### Requirement: A finished step is sent once
A step that was already sent unmarked SHALL NOT be sent again unless the source reports a different value for
it.

#### Scenario: Overlapping polls
- **WHEN** two polls both return the finished step 14:05:10 with the same value
- **THEN** only the first poll sends it

### Requirement: Each metric line is described once
The first time a metric line is seen, and whenever its description changes, the system SHALL send its
description before its numbers. Its kind and unit SHALL come from the settings rules, else from the source's
own metadata, else be marked unknown.

#### Scenario: New line
- **WHEN** a panel returns a line never seen before
- **THEN** its description is sent first, then its numbers

#### Scenario: Unit unknown to the source
- **GIVEN** a settings rule saying `lab.*.latency` is a latency in milliseconds
- **WHEN** the source has no unit for `lab.api.latency`
- **THEN** the line is described as a latency in milliseconds, classified by that rule

### Requirement: Stay within the source's limits
The system SHALL stay within the request limits the source reports. When the source answers that the limit is
reached, it SHALL wait until the reported reset, then resume every watched panel.

#### Scenario: Throttled by the source
- **WHEN** the source answers "too many requests" with a reset in 5 seconds
- **THEN** no request is sent for 5 seconds
- **AND** afterwards every watched panel is collected again

### Requirement: One failing panel does not stop the others
A failed request SHALL be retried a configured number of times with growing, randomised waits. A panel that
keeps failing SHALL not delay the other panels, and the health status SHALL report it.

#### Scenario: One panel's question is broken
- **WHEN** one panel's question is rejected by the source on every try
- **THEN** all other panels keep being collected on time
- **AND** the health status names the failing panel

### Requirement: Read-only toward the source
The system SHALL only read from the source. It SHALL NOT create, change or delete anything there.

#### Scenario: A full day of collection
- **WHEN** the Collector runs for a day
- **THEN** nothing in the source has been created or changed by it

### Requirement: Record and replay
When recording is switched on in settings, every answer from the source SHALL be saved to files. A replay
source SHALL run the Collector from those files with no connection to the source, producing the same messages.

#### Scenario: Testing after the trial ends
- **GIVEN** a recording made during a live run
- **WHEN** the Collector runs with the replay source
- **THEN** it sends the same descriptions and points as the live run, without contacting the source
