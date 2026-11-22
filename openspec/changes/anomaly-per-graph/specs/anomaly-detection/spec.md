## ADDED Requirements

### Requirement: One anomaly per graph
With `incident-scope: graph` (the default) the Detector SHALL put a misbehaving line into the anomaly of its graph: its
region, its dashboard, its panel (by widget id, else by panel title) and, inside a split group, the small graph for its
value of the split tag. Lines of the same graph that misbehave together SHALL share one anomaly; lines of different
graphs SHALL never share one. A line drawn on several graphs SHALL belong to the graph of its first place.

#### Scenario: Many panels, no service tag
- **WHEN** lines of three panels of one dashboard, tagged only by client and status code, misbehave at the same time
- **THEN** there are three anomalies, one per panel, each with its own lines and its own reason

#### Scenario: Small graphs of a split group
- **WHEN** the split group "Success Rates" (one graph per `api`) has `/v1/devices` and `/v1/invoices` dropping
- **THEN** there are two anomalies, one per small graph

#### Scenario: Two regions
- **WHEN** the same dashboard watched in US and EU misbehaves in both
- **THEN** each region has its own anomaly

### Requirement: The grouping is a setting
`incident-scope` in `detector.yml` SHALL choose `graph` (default) or `service`, where `service` keeps the grouping by
the first `group-by` tag a line has, else its dashboard. An anomaly open when the setting changes SHALL keep its lines.

#### Scenario: Back to service
- **WHEN** `incident-scope: service` is committed
- **THEN** new anomalies are grouped by service tag again, and those already open are unchanged

### Requirement: An anomaly's title names its graph
With `incident-scope: graph`, an anomaly's title SHALL name its panel and, for a small graph, the tag value, followed by
what happened, for example "Request Counts by Partner · partner=north: traffic up, traffic silent".

#### Scenario: Title
- **WHEN** the `/v1/devices` small graph of "Success Rates" has a success-rate drop
- **THEN** the title is "Success Rates · api=/v1/devices: success rate down"
