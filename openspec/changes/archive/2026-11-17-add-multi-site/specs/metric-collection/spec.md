# Spec Delta

## ADDED Requirements

### Requirement: Several sources are watched at once
The settings SHALL list one or more sources, each with a short unique name, its site, the names of its two keys,
its dashboards and their filters; each source SHALL use a different site. All sources SHALL be collected at the
same time. Settings that name only one site
and its dashboards SHALL keep working as a single source, with the same line ids as before.

#### Scenario: US and EU together
- **GIVEN** sources `us` (`datadoghq.com`) and `eu` (`datadoghq.eu`), each with its own dashboards
- **WHEN** the Collector starts
- **THEN** lines of both sources are sent within one poll interval, and no US line id equals an EU line id

#### Scenario: Same query in both regions
- **WHEN** a US dashboard and an EU dashboard ask the same question
- **THEN** each source asks its own site, and the two answers become different lines

#### Scenario: Old single-site settings
- **GIVEN** a `collector.yml` with only `datadog-site` and `dashboards`
- **WHEN** the Collector starts
- **THEN** it runs as one source, and every line id equals the id it had before this change

#### Scenario: Duplicate source name or site
- **WHEN** two sources are given the same name, or the same site
- **THEN** the settings are refused, the previous settings stay, and the settings page says which two clash

### Requirement: A source's trouble stays in that source
Each source SHALL have its own request budget, its own wait after "too many requests", its own limit on requests
in flight, its own failure breakers and its own metric metadata. Throttling, an outage or slow answers in one
source SHALL NOT delay, slow or pause another source's collection.

#### Scenario: EU throttled
- **WHEN** the EU site answers "too many requests" with a reset in 30 seconds
- **THEN** no EU request is sent for 30 seconds, and US requests keep their usual timing

#### Scenario: EU unreachable
- **WHEN** the EU site cannot be reached for 10 minutes
- **THEN** EU panels are paused by their breakers and reported, and every US panel is collected on time

#### Scenario: EU slow
- **WHEN** every EU answer takes 10 seconds
- **THEN** EU requests in flight never exceed EU's own limit, and US requests in flight and timing are unchanged

### Requirement: Sources do not burst together
The first poll of every panel SHALL be spread across the poll interval, independently of its source, so that the
sources' requests do not start at the same moment.

#### Scenario: Two sources start together
- **GIVEN** two sources with 100 panels each and a 15-second interval
- **WHEN** the Collector starts
- **THEN** no one second of the first interval holds more than a quarter of the first polls

### Requirement: Each source's budget is checked in advance
For each source the system SHALL compute the share of its request limit that the planned polls use, and report it.
Above 90% it SHALL warn, naming the source, before the site refuses any request.

#### Scenario: Too many dashboards for one account
- **GIVEN** the EU source's planned polls need 120% of its reported limit
- **WHEN** the plan is made
- **THEN** the health page shows 120% for `eu` with a warning, and the US share is shown separately

### Requirement: Sources change without a restart
Adding, removing or changing a source or its dashboards in the settings SHALL take effect without a restart when
its keys are in the environment. A source whose keys are missing SHALL be reported as "keys missing" and SHALL NOT
stop the other sources.

#### Scenario: Adding the EU source
- **GIVEN** the EU keys are in the Collector's environment
- **WHEN** the `eu` source is added to the settings and committed
- **THEN** EU lines are sent within one discovery cycle, and US collection is not interrupted

#### Scenario: Keys missing
- **WHEN** a source names keys that are not in the environment
- **THEN** its health says "keys missing" and the other sources run normally

#### Scenario: API key without application key
- **GIVEN** `DD_EU_API_KEY` is in the environment but `DD_EU_APP_KEY` is not
- **WHEN** the `eu` source is configured
- **THEN** setup refuses to finish and names the missing application key; and if the Collector runs anyway, `eu`
  health says "keys missing: application key", no EU request is sent, and `us` runs normally

### Requirement: Health shows each source
The Collector's health SHALL show, per source: panels watched, budget share, requests and failures, whether its
wait after "too many requests" is active, open breakers, and whether its keys are present (never their values).

#### Scenario: Two sources running
- **WHEN** the health page is read with `us` and `eu` running
- **THEN** it shows one block for `us` and one for `eu`, each with its own numbers

#### Scenario: A source whose dashboards cannot be read
- **WHEN** none of the `eu` dashboards can be read (for example its site is unreachable)
- **THEN** health warns "eu: 2 of 2 dashboard(s) cannot be read", the source tries again every minute, and `us`
  keeps its timing

## MODIFIED Requirements

### Requirement: Record and replay
When recording is switched on in settings, every answer from the source SHALL be saved to files, each source in its
own folder. A replay source SHALL run the Collector from those files with no connection to the source, producing
the same messages; a recording of several sources SHALL replay them together.

#### Scenario: Testing after the trial ends
- **GIVEN** a recording made during a live run
- **WHEN** the Collector runs with the replay source
- **THEN** it sends the same descriptions and points as the live run, without contacting the source

#### Scenario: Replaying two sources
- **GIVEN** a recording of sources `us` and `eu`
- **WHEN** the Collector replays it
- **THEN** lines of both sources are sent, with the same ids as in the live run
