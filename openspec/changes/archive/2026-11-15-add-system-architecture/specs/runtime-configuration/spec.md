# Spec Delta

## Purpose

Keeps every setting of the system outside the code, so the people running it can change what is watched and
how it behaves without changing, rebuilding or redeploying any service.

## ADDED Requirements

### Requirement: Settings live outside the code
The system SHALL read all operational settings from configuration files kept outside the service code:
watched dashboards, polling interval, detection rules, alert routing, report time, data retention and the
meaning (kind and unit) of metrics. No such value SHALL be fixed in code.

#### Scenario: Add a dashboard to watch
- **WHEN** an operator adds a dashboard ID to the dashboards setting and commits it to the configuration repository
- **THEN** the Collector's current settings, shown on its settings endpoint, include the new ID
- **AND** no code was changed, rebuilt or redeployed

#### Scenario: Each service sees only its own settings plus shared ones
- **WHEN** the Reporter starts
- **THEN** it receives the shared settings and the Reporter settings, and not the Collector's

### Requirement: Changes apply without a restart
The system SHALL apply a saved configuration change to the running services within 60 seconds, without
restarting them.

#### Scenario: Poll interval change
- **WHEN** an operator changes the poll interval from 15 seconds to 30 seconds and commits the change
- **THEN** within 60 seconds the Collector's settings endpoint shows 30 seconds
- **AND** the Collector's process was not restarted

### Requirement: Invalid settings are rejected safely
The system SHALL check a configuration change before using it. An invalid change SHALL be rejected as a
whole, the last valid settings SHALL stay in use, and the error SHALL be reported in the service logs and
health status.

#### Scenario: Typo in a setting
- **WHEN** an operator commits a poll interval of "-5 seconds"
- **THEN** the services keep running with the previous valid settings
- **AND** the logs and the health status name the invalid setting

### Requirement: No secrets in configuration files
Configuration files SHALL NOT contain API keys or other secrets. Secrets SHALL come from the environment
of each service, and SHALL NOT appear in logs, reports or messages.

#### Scenario: Searching the configuration for keys
- **WHEN** the configuration repository is searched for the Datadog API or application key
- **THEN** no match is found

#### Scenario: Startup log
- **WHEN** the Collector starts and logs its settings
- **THEN** the log shows that keys are present, but never their values
