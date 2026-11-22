## ADDED Requirements

### Requirement: Anomalies are listed by panel
On the Anomalies page, anomalies grouped by graph SHALL be listed as region › dashboard › panel › anomaly, the small
graph's tag value shown on the anomaly; anomalies grouped by service keep their service level. In the Worst anomalies
strip the first column SHALL name the graph.

#### Scenario: Tree
- **WHEN** a dashboard has anomalies on "Request Counts" and on two small graphs of "Success Rates"
- **THEN** the page lists the dashboard with the panels "Request Counts" (one anomaly) and "Success Rates" (two anomalies, `api=/v1/devices` and `api=/v1/invoices`)
