# Spec Delta

## MODIFIED Requirements

### Requirement: Find every panel on the watched dashboards
The system SHALL read each watched dashboard from its JSON file in the settings (as exported from Datadog) and find
every metric question on it, including inside groups and split groups, tables and other charts. Panels with no
metric question SHALL be skipped. The dashboard API SHALL NOT be used to read dashboards.

#### Scenario: Panels inside groups
- **WHEN** a watched dashboard's file holds timeseries panels inside a group and a split group
- **THEN** every metric question of those panels is collected

#### Scenario: Panel added to a dashboard
- **WHEN** the user exports the dashboard again with a new panel, replaces its file and commits
- **THEN** the new panel's numbers are collected within one poll interval, with no restart

#### Scenario: Panel removed
- **WHEN** the dashboard's file no longer holds a panel and the change is committed
- **THEN** that panel stops being collected within one poll interval

### Requirement: Dashboard filters are filled in
Placeholders in a dashboard's questions (such as `$env`) SHALL be filled from the `filters` given next to the
dashboard's file in the settings, or else from the default of that variable in the file. A placeholder whose value
is "all" (`*`) SHALL be dropped from the filter.

#### Scenario: Value from the file
- **GIVEN** the file's variable `env` has the default `prod` and `team` the default `*`
- **WHEN** a panel asks for `sum:requests{$env,$team}`
- **THEN** the Collector asks Datadog for `sum:requests{env:prod}`

#### Scenario: Value from settings
- **GIVEN** the file's variable `service` has the default `*` and the settings give `filters: { service: payments }`
- **WHEN** a panel asks for `avg:latency{$service}`
- **THEN** the Collector asks Datadog for `avg:latency{service:payments}`

#### Scenario: Default changed in the file
- **WHEN** the file's `env` default is changed from `prod` to `stage` and committed
- **THEN** the next polls ask for `env:stage`, with no restart

## ADDED Requirements

### Requirement: Dashboards are given as files, not ids
Each source SHALL list its dashboards as JSON files in the settings repository, each with an optional `id` (for
links) and optional `filters`. A dashboard given as an id alone SHALL be refused, with a message saying how to
export the JSON. Line ids SHALL NOT depend on whether a dashboard came from a file or from an id.

#### Scenario: An id instead of a file
- **WHEN** a source lists `dashboards: [abc-def-ghi]`
- **THEN** the settings are refused, the previous settings stay, and the message says to export the dashboard as
  JSON into `config-repo/dashboards/`

#### Scenario: Same lines as when read by id
- **GIVEN** the lab dashboards were collected by id before this change
- **WHEN** the same dashboards are given as their JSON files with the same filters
- **THEN** every line id is the same as before

### Requirement: Dashboard links need the dashboard's id
Incident links to a dashboard SHALL use the id in the dashboard's file, or else the `id` given next to it in the
settings. A dashboard with neither SHALL be collected normally; its incidents SHALL have no dashboard link and the
Collector's health SHALL say so.

#### Scenario: Export without an id
- **GIVEN** a dashboard file with no `id` and no `id` next to it in the settings
- **WHEN** one of its lines opens an incident
- **THEN** the incident has no link to that dashboard, and the Collector's health lists the dashboard as "no id
  for links"

### Requirement: Panels are checked before running
A check SHALL ask each panel's question of each watched dashboard once (read-only, the last 15 minutes), using the
same filling and planning as the running Collector, and print per panel the question asked and how many lines came
back.

#### Scenario: A filter that matches nothing
- **GIVEN** a dashboard file whose `env` default is `nosuchenv`
- **WHEN** the check runs
- **THEN** it prints each panel with "0 lines" and the filled question, for example `avg:latency{env:nosuchenv}`

#### Scenario: A good dashboard
- **WHEN** the check runs for a dashboard whose filters match data
- **THEN** it prints each panel with its number of lines, and ends with how many panels returned lines

### Requirement: Panels that return nothing are reported
While running, the Collector's health SHALL list per source and dashboard the panels whose latest answer held no
lines, with the filters used. When every panel of a dashboard returns no lines, health SHALL be `DEGRADED` with a
warning naming the dashboard.

#### Scenario: A whole dashboard returns nothing
- **GIVEN** every panel of the dashboard "Shop" returns no lines
- **WHEN** health is read
- **THEN** it is `DEGRADED` with "us: dashboard Shop: 12 of 12 panels return no lines (filters env:prod,
  service:*)"

#### Scenario: Some panels return nothing
- **GIVEN** 2 of 12 panels of "Shop" return no lines
- **WHEN** health is read
- **THEN** it lists those 2 panels with their filters, and stays `UP` if nothing else is wrong

### Requirement: Saved views of a dashboard can be watched
Next to a dashboard file, `presets: all` SHALL watch every saved view (preset) of that dashboard, and a list of names
SHALL watch only those; without `presets`, the file's defaults SHALL be used. Each watched preset SHALL be its own
view named "<dashboard> · <preset>". A variable a preset does not set SHALL keep its default from the file; `filters`
next to the file SHALL apply to every view. An unknown preset name SHALL be refused, listing the dashboard's presets.

#### Scenario: All presets
- **GIVEN** a dashboard file with 23 presets and `presets: all`
- **WHEN** the Collector plans it
- **THEN** each of the 23 presets is a view, and every panel's question is filled with that preset's values

#### Scenario: Preset that sets only some variables
- **GIVEN** the preset "Profiles" sets `team`, `gateway_service`, `service` and `env` only, and the file's `region` default is `*`
- **WHEN** the Collector fills `count:svc.api.duration{$team,$service,$env,$region}`
- **THEN** it asks for `count:svc.api.duration{team:platform,service:profiles,env:prod}`

#### Scenario: Unknown preset
- **WHEN** `presets: [Payments, Nope]` is given
- **THEN** the settings are refused and the message lists the dashboard's presets

#### Scenario: Same question from two presets
- **GIVEN** the presets "Payments" and "Payments Worker" give a gateway panel the same question
- **WHEN** the Collector plans the dashboard
- **THEN** that question is asked once, and its lines are shown under both views

### Requirement: A variable can have several values
When a preset gives a variable several values, the question SHALL match any of them, written as
`prefix IN (value1,value2)`, combined with the other filters in Datadog's boolean form.

#### Scenario: Two teams
- **GIVEN** the preset "Payments" gives `team` the values `web` and `mobile`, `service` the value `payments` and `env` `prod`
- **WHEN** a panel asks for `count:svc.api.duration{$team,$service,$env,!api:/health}`
- **THEN** the Collector asks for `count:svc.api.duration{team IN (web,mobile) AND service:payments AND env:prod AND NOT api:/health}`

### Requirement: Panels under a section heading are named by it
A top-level panel placed below an empty group SHALL be named with that group's title as its section, until the next
group, so panels with the same title in different sections can be told apart.

#### Scenario: Two Latencies panels
- **GIVEN** an empty group "API Metrics" followed by a panel "Latencies", and later an empty group "Upstream Service Metrics" followed by another "Latencies"
- **WHEN** the dashboard is read
- **THEN** the panels are "API Metrics / Latencies" and "Upstream Service Metrics / Latencies"

