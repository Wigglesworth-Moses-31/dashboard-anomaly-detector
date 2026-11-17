# Spec Delta

## ADDED Requirements

### Requirement: Dashboard links open the right site
An incident's dashboard links SHALL open the dashboard on the site its line came from, for every Datadog site,
at the incident's time.

#### Scenario: EU incident
- **WHEN** an incident is opened for a line from site `datadoghq.eu`
- **THEN** its link starts with `https://app.datadoghq.eu/dashboard/`

#### Scenario: US1 and US3 incidents
- **WHEN** incidents are opened for lines from `datadoghq.com` and `us3.datadoghq.com`
- **THEN** their links start with `https://app.datadoghq.com/dashboard/` and `https://us3.datadoghq.com/dashboard/`

### Requirement: The site is named when there are several
When lines from more than one site are watched, an incident's title and each line's panel SHALL name the site by
its short label (US1, US3, US5, EU, AP1...), and lines from different sites SHALL never be grouped into one incident.

#### Scenario: Same API in both regions
- **GIVEN** sources `us` and `eu`, both with lines tagged `api:/v1/invoices`
- **WHEN** latency rises in both regions at the same time
- **THEN** two incidents open, titled "EU · /v1/invoices: latency up" and "US1 · /v1/invoices: latency up"

#### Scenario: One source only
- **WHEN** lines from only one site are watched
- **THEN** titles stay as before, without a source name
