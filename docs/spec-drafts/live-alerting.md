# Spec Delta

## Purpose

Shows incidents live on a web page the moment the system finds them, so people see problems without
watching dashboards, and can jump straight to the right dashboard and time.

## ADDED Requirements

### Requirement: New incidents appear live
An open web page SHALL show a new incident within 5 seconds after the system opens it, without the user
reloading the page. Updates to the incident (new metric lines, new peak, closed) SHALL appear the same way.

#### Scenario: Incident opens
- **GIVEN** the alert page is open
- **WHEN** the system opens an incident
- **THEN** the incident appears on the page within 5 seconds

#### Scenario: Incident closes
- **WHEN** an open incident closes
- **THEN** the page shows it as closed within 5 seconds

### Requirement: Catch up on connect
When a web page connects or reconnects, it SHALL first receive every currently open incident, then live
updates.

#### Scenario: Laptop wakes up
- **WHEN** a page loses its connection for 10 minutes and reconnects
- **THEN** it shows every incident that is open at that moment, including ones opened while it was away

### Requirement: Slow viewers never slow the system
A slow or stalled web page SHALL NOT delay detection or other viewers. A page that cannot keep up SHALL
receive the latest state of each incident and may skip intermediate updates.

#### Scenario: One stalled browser tab
- **WHEN** one viewer's connection stalls while incidents are changing
- **THEN** other viewers still get updates within 5 seconds, and detection is not delayed

### Requirement: Each alert links to the evidence
Each incident SHALL include links that open the related Datadog dashboard at the incident's time window.

#### Scenario: Open the dashboard from an alert
- **WHEN** the user clicks the dashboard link of an incident that started at 14:05
- **THEN** the dashboard opens showing the time around 14:05
