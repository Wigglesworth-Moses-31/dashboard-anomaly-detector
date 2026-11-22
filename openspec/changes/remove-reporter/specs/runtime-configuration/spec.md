## MODIFIED Requirements

### Requirement: Settings live outside the code
The system SHALL read all operational settings from configuration files kept outside the service code:
watched dashboards, polling interval, detection rules, alert routing, data retention and the meaning (kind and unit)
of metrics. No such value SHALL be fixed in code.

#### Scenario: Add a dashboard to watch
- **WHEN** an operator adds a dashboard ID to the dashboards setting and commits it to the configuration repository
- **THEN** the Collector's current settings, shown on its settings endpoint, include the new ID
- **AND** no code was changed, rebuilt or redeployed

#### Scenario: Each service sees only its own settings plus shared ones
- **WHEN** the Detector starts
- **THEN** it receives the shared settings and the Detector settings, and not the Collector's
