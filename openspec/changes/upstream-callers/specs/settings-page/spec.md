## ADDED Requirements

### Requirement: Upstreams are edited in the Settings tab
The Settings tab SHALL have an Upstreams section showing `upstreams.yml` in an editor with Save. Saving SHALL check the
shape (regions are us or eu; each upstream has a list of APIs) and write one commit on top of the commit shown; a wrong
shape or a change made meanwhile SHALL be refused with the reason, nothing written.

#### Scenario: Save
- **WHEN** `telemetry-ingest: [/v1/telemetry]` is added under `us` and saved
- **THEN** one commit "settings page: upstreams" is made and the page shows the new callers at once

#### Scenario: Wrong shape
- **WHEN** a region `asia` is saved
- **THEN** the page says "regions must be us or eu" and nothing is written
