# metric-storage Specification

## Purpose
Keeps every metric line and number produced by the Collector, without losing or duplicating any, and answers
questions about their history and each day's summary.

## Requirements

### Requirement: Every number is kept once
Every point received SHALL be stored with its latest value, once per metric line and time: a point received again
replaces the stored value instead of adding a second one. Points SHALL be stored even if their line's description
has not arrived yet.

#### Scenario: The same step delivered twice
- **WHEN** the point for 14:05:10 of a line arrives twice
- **THEN** the store holds one value for that line at 14:05:10

#### Scenario: A corrected value
- **WHEN** the point for 14:05:10 arrives with value 5, and later with value 6
- **THEN** the store holds 6 for 14:05:10

#### Scenario: Numbers before their description
- **WHEN** points for a line arrive before that line's description
- **THEN** the points are stored, and the description is stored when it arrives

### Requirement: Nothing is lost on a crash
A message SHALL be acknowledged to Kafka only after everything it contains is saved. After a crash or restart the
Store SHALL continue from the first message not yet saved.

#### Scenario: Store stopped while numbers arrive
- **WHEN** the Store is stopped in the middle of a stream of messages and started again
- **THEN** every point sent while it was down or mid-batch is stored, and none is stored twice

### Requirement: Broken messages are set aside
A message that fails the shared contract checks SHALL NOT be stored. It SHALL be counted, logged with the reason,
and skipped without stopping the messages after it.

#### Scenario: Points without a series id
- **WHEN** a points message without a series id arrives between two good ones
- **THEN** both good messages are stored, the broken one is not, and the count of set-aside messages goes up by one

### Requirement: One line's history on request
The Store SHALL answer a history question for one metric line and a time range, at raw (10-second) or per-minute
resolution, oldest first, in chunks of at most 1000 points. A line with no data SHALL get an empty answer.

#### Scenario: Last hour per minute
- **WHEN** the Detector asks for the last hour of a line per minute
- **THEN** it receives up to 60 minute values, oldest first

#### Scenario: Long raw range
- **WHEN** a client asks for 2 days of raw points of one line
- **THEN** the answer arrives as a stream of chunks, none holding more than 1000 points

### Requirement: Summary of any time window
The Store SHALL answer, for any time window (start and end), one summary per metric line: number of points,
lowest, highest, average and 95th percentile of the points inside the window. Windows longer than the configured
maximum (default 31 days) SHALL be refused.

#### Scenario: The last 6 hours
- **WHEN** a client asks for the summary from 06:00 to 12:00 UTC
- **THEN** each line's summary covers exactly the points from 06:00 up to, not including, 12:00

#### Scenario: Window too long
- **WHEN** a client asks for a 40-day window
- **THEN** the question is refused with a reason

### Requirement: Summary of one day
The Store SHALL answer, for one calendar day in a given time zone, the same summary as for the window from that
day's midnight to the next midnight in that time zone.

#### Scenario: Yesterday's summary in India time
- **WHEN** the Reporter asks for the summary of 2026-11-15 in Asia/Kolkata
- **THEN** each line's summary covers 2026-11-15 00:00 to 24:00 India time

#### Scenario: A day equals its window
- **WHEN** the same day is asked for as a day and as the window from its midnight to the next
- **THEN** both answers are identical

### Requirement: Storage stays small
Raw points SHALL be kept until the Reporter removes them, and never longer than the configured safety limit
(default 2 days, at most 7). Per-minute summaries SHALL be kept for the configured time (default 2 days, at most
7). This is a lab system: no setting may keep data longer.

#### Scenario: The Reporter did not run for three days
- **WHEN** no clean-up has happened for 3 days
- **THEN** raw points and per-minute summaries older than 2 days are gone

#### Scenario: Asking to keep data for a month
- **WHEN** an operator sets the raw safety limit to 30 days
- **THEN** the setting is rejected and the previous limit stays in use

### Requirement: Size alarm
The Store SHALL check the database's size on disk every minute. Above the configured limit (default 1 GB), its
health SHALL turn DEGRADED and name the total size and the largest table.

#### Scenario: Clean-up stopped working
- **WHEN** the database grows past 1 GB
- **THEN** the Store's health is DEGRADED and shows the size and the largest table, within one minute

### Requirement: Keeps up with the Collector
At the lab's rate, the Store SHALL save a point within 5 seconds of receiving it, in 95% of cases.

#### Scenario: Replay of the 42-minute recording
- **WHEN** the Collector replays its 42-minute recording into Kafka while the Store runs
- **THEN** 95% of points are saved within 5 seconds of arriving, and the store ends with every finished point of the recording

### Requirement: Health shows how the Store is doing
The Store's health SHALL show how far behind Kafka it is, when it last saved, how many points it saved and how
many messages it set aside.

#### Scenario: Store falls behind
- **WHEN** the database is slow and the Store falls behind
- **THEN** the health page shows the number of messages still waiting

### Requirement: Raw points removed on request
The Store SHALL answer a request to remove raw points older than a given time, removing only raw points (never
per-minute summaries or line descriptions) and never points from the last 3 hours, which the per-minute summaries are
refreshed from. It SHALL refuse a time in the future and answer how many points it removed and up to which time.

#### Scenario: The Reporter cleans the day
- **WHEN** at 00:02 on the 17th the Reporter asks to remove raw points older than 23:00 India time on the 16th
- **THEN** raw points before 21:02 are gone, those from 21:02 on remain, the per-minute summaries of the whole 16th
  remain, and the answer says 21:02 and how many points were removed

#### Scenario: The safety limit still applies
- **WHEN** no clean-up request comes for 3 days
- **THEN** raw points older than 2 days are still removed by the Store's own limit

#### Scenario: A time in the future
- **WHEN** a request asks to remove raw points older than tomorrow
- **THEN** it is refused with a reason and nothing is removed
