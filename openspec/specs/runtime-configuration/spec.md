# runtime-configuration Specification

## Purpose
Keeps every setting of the system outside the code, so the people running it can change what is watched and
how it behaves without changing, rebuilding or redeploying any service.

## Requirements

### Requirement: Settings live outside the code
The system SHALL read all operational settings from configuration files kept outside the service code:
watched dashboards, polling interval, detection rules, alert routing, report time, data retention and the
meaning (kind and unit) of metrics. No such value SHALL be fixed in code.

#### Scenario: Add a dashboard to watch
- **WHEN** an operator adds a dashboard ID to the dashboards setting and commits it to the configuration repository
- **THEN** the Collector's current settings, shown on its settings endpoint, include the new ID
- **AND** no code was changed, rebuilt or redeployed

#### Scenario: Each service sees only its own settings plus shared ones
- **WHEN** the Reporter starts
- **THEN** it receives the shared settings and the Reporter settings, and not the Collector's

### Requirement: Changes apply without a restart
The system SHALL apply a saved configuration change to the running services within 60 seconds, without
restarting them.

#### Scenario: Poll interval change
- **WHEN** an operator changes the poll interval from 15 seconds to 30 seconds and commits the change
- **THEN** within 60 seconds the Collector's settings endpoint shows 30 seconds
- **AND** the Collector's process was not restarted

### Requirement: Invalid settings are rejected safely
The system SHALL check a configuration change before using it. An invalid change SHALL be rejected as a
whole, the last valid settings SHALL stay in use, and the error SHALL be reported in the service logs and
health status.

#### Scenario: Typo in a setting
- **WHEN** an operator commits a poll interval of "-5 seconds"
- **THEN** the services keep running with the previous valid settings
- **AND** the logs and the health status name the invalid setting

### Requirement: No secrets in configuration files
Configuration files SHALL NOT contain API keys or other secrets. Secrets SHALL come from the environment
of each service, and SHALL NOT appear in logs, reports or messages.

#### Scenario: Searching the configuration for keys
- **WHEN** the configuration repository is searched for the Datadog API or application key
- **THEN** no match is found

#### Scenario: Startup log
- **WHEN** the Collector starts and logs its settings
- **THEN** the log shows that keys are present, but never their values

### Requirement: Settings name secrets, and only allowed ones
Settings MAY name the environment variable that holds a secret, never hold the secret itself. A Datadog key name
SHALL match `DD_<NAME>_API_KEY` or `DD_<NAME>_APP_KEY` (or the original `DD_API_KEY` and `DD_APP_KEY`); any other
name SHALL be refused, so that settings can never make a service read an unrelated secret.

#### Scenario: Allowed key name
- **WHEN** a source names `DD_EU_API_KEY` and `DD_EU_APP_KEY`
- **THEN** the settings are accepted and the Collector reads those two variables

#### Scenario: Pointing at another secret
- **WHEN** a source names `PLATFORM_DB_PASSWORD` as its API key
- **THEN** the settings are refused, the old settings stay, and the settings page says the name is not allowed

#### Scenario: Only Datadog keys reach the Collector
- **WHEN** the system is started with `.env` holding keys for `us` and `eu` and the database password
- **THEN** the Collector's environment holds the four Datadog keys and not the database password

### Requirement: Dashboard files are settings
Dashboard JSON files SHALL live in the settings repository, be served by the Config Service like other settings,
be checked before use and apply without a restart. A file that is not valid JSON, holds no widgets, or whose
`filters` name a variable the dashboard does not have SHALL be refused, keeping the previous version.

#### Scenario: Replacing a dashboard file
- **WHEN** a dashboard's JSON file is replaced and the settings repository is committed
- **THEN** the Collector uses the new file within seconds, without a restart

#### Scenario: Broken file
- **WHEN** a committed dashboard file is not valid JSON
- **THEN** it is refused, the previous version keeps being used, and the Collector's settings page names the file
  and the problem

#### Scenario: Unknown variable in filters
- **WHEN** `filters: { envv: prod }` is given for a dashboard that has no variable `envv`
- **THEN** the settings are refused and the message lists the dashboard's variables

### Requirement: Times are shown in several zones
The shared settings SHALL hold the display time zones (default UTC, Asia/Kolkata, America/New_York): one to four valid
zones, the first being the main one. A change SHALL apply without a restart; an unknown zone or an empty list SHALL be
refused, keeping the previous list.

#### Scenario: Default zones
- **WHEN** the settings give no display zones
- **THEN** times are shown in UTC, then Asia/Kolkata, then America/New_York

#### Scenario: A typo
- **WHEN** the display zones are set to `[UTC, Asia/Kolkatta]`
- **THEN** the change is refused and the previous zones stay

### Requirement: Today is the UTC day by default
The shared time zone, which decides the page's day and a daily report's window, SHALL default to UTC in the settings
template and in setup.

#### Scenario: A new installation
- **WHEN** setup creates the settings without a time zone given
- **THEN** the shared time zone is UTC

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
