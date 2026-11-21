# Spec Delta

## REMOVED Requirements

### Requirement: Messages are kept 10 days, compressed
**Reason**: Replaced by "Messages are kept 24 hours, compressed" (user, 21 Nov 2026).
**Migration**: The topics are changed in place by `scripts/system.sh start`.

## ADDED Requirements

### Requirement: Messages are kept 24 hours, compressed
Every topic SHALL keep its messages 24 hours, and every producer SHALL compress its messages (zstd). Existing topics
SHALL be changed in place when the system starts, without losing messages. Incidents still waiting SHALL be announced
again at least every 12 hours, so a reader rebuilding from 24 hours of events still knows them.

#### Scenario: Topic settings
- **WHEN** the topics are described after start
- **THEN** each shows a retention of 24 hours

#### Scenario: Waiting for two days
- **GIVEN** an incident calm for 2 days without a reason
- **WHEN** the Notifier restarts
- **THEN** it knows the incident from its latest announcement
