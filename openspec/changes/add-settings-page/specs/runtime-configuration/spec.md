# Spec Delta

## ADDED Requirements

### Requirement: The system may change dashboard settings as commits
The system SHALL be able to add, replace and remove dashboard files and their entries in the settings repository,
each change as one commit with a message saying what changed, so changes from the page are visible and can be undone
with Git like hand edits. It SHALL change nothing else in the settings.

#### Scenario: One action, one commit
- **WHEN** a dashboard is added from the page
- **THEN** the settings repository has one new commit "settings page: add eu/shop.json" touching only that file and `collector.yml`

### Requirement: The page and its settings API are local only
The services that serve the page and change settings SHALL accept connections only from the same machine.

#### Scenario: From another machine
- **WHEN** another machine on the network opens the page's address
- **THEN** the connection is refused
