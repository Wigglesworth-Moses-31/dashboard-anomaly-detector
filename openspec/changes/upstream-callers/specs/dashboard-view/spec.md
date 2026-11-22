## ADDED Requirements

### Requirement: An upstream anomaly names its callers
For a line whose upstream tag names an upstream in `upstreams.yml` for its region, the details panel and the Anomalies
page SHALL show "Called by": each API with its state now (open, needs a reason, fine) and its largest change, each
leading to its line. An upstream not in the file SHALL say that its callers are not configured.

#### Scenario: Slow upstream
- **WHEN** `upstream_service=billing-core` latency is up and `/v1/invoices` latency is up too
- **THEN** the panel shows "Called by: /v1/invoices (open, latency up 4×), /v1/payments (fine)"

### Requirement: An API anomaly names what it calls
For a line whose caller tag names an API listed in `upstreams.yml` for its region, the details panel SHALL show
"Calls": each upstream it depends on with its state now.

#### Scenario: Slow API
- **WHEN** `/v1/invoices` latency is up
- **THEN** its panel shows "Calls: billing-core (open, latency up 3×)"
