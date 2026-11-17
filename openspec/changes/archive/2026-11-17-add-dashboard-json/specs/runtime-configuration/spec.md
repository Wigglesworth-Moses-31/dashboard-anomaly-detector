# Spec Delta

## ADDED Requirements

### Requirement: Dashboard files are settings
Dashboard JSON files SHALL live in the settings repository, be served by the Config Service like other settings,
be checked before use and apply without a restart. A file that is not valid JSON, holds no widgets, or whose
`filters` name a variable the dashboard does not have SHALL be refused, keeping the previous version.

#### Scenario: Replacing a dashboard file
- **WHEN** a dashboard's JSON file is replaced and the settings repository is committed
- **THEN** the Collector uses the new file within seconds, without a restart

#### Scenario: Broken file
- **WHEN** a committed dashboard file is not valid JSON
- **THEN** it is refused, the previous version keeps being used, and the Collector's settings page names the file
  and the problem

#### Scenario: Unknown variable in filters
- **WHEN** `filters: { envv: prod }` is given for a dashboard that has no variable `envv`
- **THEN** the settings are refused and the message lists the dashboard's variables
