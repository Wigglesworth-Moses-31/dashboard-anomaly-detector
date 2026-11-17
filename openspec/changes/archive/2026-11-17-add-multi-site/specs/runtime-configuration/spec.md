# Spec Delta

## ADDED Requirements

### Requirement: Settings name secrets, and only allowed ones
Settings MAY name the environment variable that holds a secret, never hold the secret itself. A Datadog key name
SHALL match `DD_<NAME>_API_KEY` or `DD_<NAME>_APP_KEY` (or the original `DD_API_KEY` and `DD_APP_KEY`); any other
name SHALL be refused, so that settings can never make a service read an unrelated secret.

#### Scenario: Allowed key name
- **WHEN** a source names `DD_EU_API_KEY` and `DD_EU_APP_KEY`
- **THEN** the settings are accepted and the Collector reads those two variables

#### Scenario: Pointing at another secret
- **WHEN** a source names `PLATFORM_DB_PASSWORD` as its API key
- **THEN** the settings are refused, the old settings stay, and the settings page says the name is not allowed

#### Scenario: Only Datadog keys reach the Collector
- **WHEN** the system is started with `.env` holding keys for `us` and `eu` and the database password
- **THEN** the Collector's environment holds the four Datadog keys and not the database password
