# Spec Delta

## MODIFIED Requirements

### Requirement: Size alarm
The Store SHALL check the database's size on disk every minute. Above the configured limit (default 10 GB), its
health SHALL turn DEGRADED and name the total size and the largest table.

#### Scenario: Clean-up stopped working
- **WHEN** the database grows past 10 GB
- **THEN** the Store's health is DEGRADED and shows the size and the largest table, within one minute

### Requirement: Older data is compressed
Raw points and per-minute summaries older than the configured age (default a few hours) SHALL be compressed by line
and time. Questions SHALL get the same answers from compressed data as before it was compressed. The size before and
after compression SHALL be visible on the Store's health.

#### Scenario: Same answer compressed
- **WHEN** a line's history is asked for before and after its older data is compressed
- **THEN** both answers are identical

#### Scenario: Ten days fit
- **WHEN** this machine's lines have been stored for the longest retention allowed (10 days)
- **THEN** the database stays under the size alarm, and the health shows the compressed size per table
