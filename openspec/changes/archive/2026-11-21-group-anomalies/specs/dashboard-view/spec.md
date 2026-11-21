# Spec Delta

## MODIFIED Requirements

### Requirement: Waiting anomalies are always one click away
The left navigation SHALL show how many metrics have open anomalies and how many wait for a reason; clicking it SHALL
open the Anomalies page filtered to open and waiting anomalies. It SHALL update live.

#### Scenario: Needs a reason
- **WHEN** two metrics wait for a reason
- **THEN** the navigation shows "2 need a reason" and opens the Anomalies page with them

## ADDED Requirements

### Requirement: Anomalies are grouped by region, dashboard and service
An Anomalies page SHALL list every anomaly of the kept time as a tree of region, dashboard, service (the incident's
group) and anomaly, with open and waiting counts at every level, each level collapsible. Regions and dashboards SHALL
be sorted by name, services worst first then by name, anomalies newest first. An anomaly on two dashboards SHALL be
listed under both.

#### Scenario: Two services
- **GIVEN** in US, dashboard "Shop", an open anomaly of api /v1/invoices and a closed one of service billing
- **WHEN** the Anomalies page is opened with every state shown
- **THEN** US › Shop lists "api /v1/invoices" (1 open) before "service billing", each with its anomaly

#### Scenario: Newest first
- **WHEN** a service had anomalies at 09:03 and 13:04
- **THEN** the 13:04 one is listed first

### Requirement: Each anomaly shows its lines and leads to them
Each anomaly SHALL show its state, start (every display zone), duration, and its lines with signal, direction and
peak against normal; clicking a line SHALL open its side panel on its dashboard. The page SHALL filter by state and
by text, and SHALL update live.

#### Scenario: Open a line
- **WHEN** the line `status_code=500` of an anomaly is clicked
- **THEN** its dashboard opens with that line's side panel

#### Scenario: Filter
- **WHEN** only "Needs a reason" is chosen
- **THEN** only anomalies waiting for a reason are listed, and empty levels are hidden
