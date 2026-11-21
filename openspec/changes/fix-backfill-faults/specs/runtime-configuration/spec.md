## ADDED Requirements

### Requirement: Old settings are pointed out at start
At start, the system SHALL compare the settings in `config-repo` with `config-template` for the keys the templates
have changed, and list each difference with the template's value and how to update. It SHALL NOT change
`config-repo` itself, because those files are the user's own settings.

#### Scenario: A machine with old settings
- **WHEN** a machine's `config-repo` still has `max-database-size: 1GB` while the template has `10GB`
- **THEN** the start prints that setting, both values and the file to edit, and the services still start with the user's value

#### Scenario: Settings up to date
- **WHEN** `config-repo` matches the template for those keys
- **THEN** the start prints nothing about settings
