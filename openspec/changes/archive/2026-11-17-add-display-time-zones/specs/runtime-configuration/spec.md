# Spec Delta

## ADDED Requirements

### Requirement: Times are shown in several zones
The shared settings SHALL hold the display time zones (default UTC, Asia/Kolkata, America/New_York): one to four valid
zones, the first being the main one. A change SHALL apply without a restart; an unknown zone or an empty list SHALL be
refused, keeping the previous list.

#### Scenario: Default zones
- **WHEN** the settings give no display zones
- **THEN** times are shown in UTC, then Asia/Kolkata, then America/New_York

#### Scenario: A typo
- **WHEN** the display zones are set to `[UTC, Asia/Kolkatta]`
- **THEN** the change is refused and the previous zones stay

### Requirement: Today is the UTC day by default
The shared time zone, which decides the page's day and a daily report's window, SHALL default to UTC in the settings
template and in setup.

#### Scenario: A new installation
- **WHEN** setup creates the settings without a time zone given
- **THEN** the shared time zone is UTC
