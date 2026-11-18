# Spec Delta

## ADDED Requirements

### Requirement: The system may change dashboard settings as commits
The system SHALL be able to add, replace and remove dashboard files and their entries in the settings repository,
each change as one commit with a message saying what changed, so changes from the page are visible and can be undone
with Git like hand edits. It SHALL be able to add and remove a region's entry the same way. It SHALL change nothing else in the settings.

#### Scenario: One action, one commit
- **WHEN** a dashboard is added from the page
- **THEN** the settings repository has one new commit "settings page: add eu/shop.json" touching only that file and `collector.yml`

### Requirement: The page and its settings API are local only
The services that serve the page and change settings SHALL accept connections only from the same machine.

#### Scenario: From another machine
- **WHEN** another machine on the network opens the page's address
- **THEN** the connection is refused

### Requirement: Keys live in .env and are read on reload
A region's keys SHALL be kept only in `.env` on this machine (readable only by its user) and SHALL be read from it
whenever the Collector's settings reload, so a new region or new keys need no restart. A key in `.env` SHALL win; an
environment variable SHALL be used only when `.env` has no such key (tests, CI).

#### Scenario: New keys without restart
- **WHEN** a region's keys in `.env` change and the settings reload
- **THEN** the next poll of that region uses the new keys

### Requirement: Regions live in their own settings file
Regions and their dashboards SHALL live in `regions.yml` in the settings repository, read together with
`collector.yml`, so the page never rewrites the hand-commented `collector.yml`. On a machine whose `collector.yml`
still holds them, the first change from the page SHALL move them in one commit, only when the regions read the same
after the move.

#### Scenario: First change on an older machine
- **GIVEN** `collector.yml` still holds the regions
- **WHEN** a dashboard is added from the page
- **THEN** one commit moves the regions to `regions.yml` unchanged, a second adds the dashboard, and `collector.yml` keeps its comments
