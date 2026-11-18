# Spec Delta

## ADDED Requirements

### Requirement: Past anomalies are shown as such
An anomaly found in backfill SHALL be listed on its metric page as "past · found in backfill" with its times, shaded
grey on charts, never counted as open or waiting, and SHALL NOT notify.

#### Scenario: Metric page
- **WHEN** a line's page is opened after its backfill found a spike
- **THEN** the spike is listed as "past · found in backfill", closed, with no reason box
