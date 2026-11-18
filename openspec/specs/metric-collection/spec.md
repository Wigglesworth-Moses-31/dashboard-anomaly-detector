# metric-collection Specification

## Purpose
Finds every metric shown on the watched dashboards and keeps their newest numbers flowing into the system,
in small time steps that keep short spikes visible, without overloading the data source.

## Requirements

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

### Requirement: Collect recent data in small steps that keep peaks
The system SHALL fetch each panel's numbers in steps of the configured length (default 10 seconds), on the
configured interval (default every 15 seconds). Each step SHALL hold the highest value inside it, except
counts, which SHALL hold the total for the step. On start it SHALL fetch the configured backfill (default 10
minutes); afterwards only the recent window (default 2 minutes).

#### Scenario: A 20-second spike
- **GIVEN** default settings
- **WHEN** a metric jumps for 20 seconds and then returns to normal
- **THEN** at least one collected step carries the peak value

#### Scenario: Collector start
- **WHEN** the Collector starts
- **THEN** each watched metric line has its last 10 minutes collected

### Requirement: Gaps are left out
A step for which the source has no value SHALL produce no point. It SHALL NOT be sent as zero.

#### Scenario: Missing step
- **WHEN** the source returns no value for the step at 14:05:10
- **THEN** the message has no point for 14:05:10

### Requirement: The unfinished step is marked, then completed
A step SHALL be sent marked partial until it ended at least the configured settle delay (default 60 seconds) before
the answer arrived, because the source can still be receiving its values. After that it SHALL be sent again
unmarked. A settle delay of 0 SHALL keep the old behaviour (partial only while the step's window has not ended).

#### Scenario: Step still filling
- **WHEN** a poll at 14:05:15 returns the step 14:05:10 to 14:05:20
- **THEN** that point is sent marked partial
- **AND** a later poll sends the same step unmarked

#### Scenario: Step ended but still settling
- **GIVEN** a settle delay of 60 seconds
- **WHEN** a poll at 14:05:50 returns the step 14:05:10 to 14:05:20 with a count of 1
- **THEN** that point is sent marked partial
- **AND** a poll at 14:06:25 returning the complete count of 13 sends it unmarked with 13

### Requirement: A finished step is sent once
A step that was already sent unmarked SHALL NOT be sent again unless the source reports a different value for
it.

#### Scenario: Overlapping polls
- **WHEN** two polls both return the finished step 14:05:10 with the same value
- **THEN** only the first poll sends it

### Requirement: Each metric line is described once
The first time a metric line is seen, and whenever its description changes, the system SHALL send its
description before its numbers. Its kind and unit SHALL come from the settings rules, else from the source's
own metadata, else be marked unknown.

#### Scenario: New line
- **WHEN** a panel returns a line never seen before
- **THEN** its description is sent first, then its numbers

#### Scenario: Unit unknown to the source
- **GIVEN** a settings rule saying `lab.*.latency` is a latency in milliseconds
- **WHEN** the source has no unit for `lab.api.latency`
- **THEN** the line is described as a latency in milliseconds, classified by that rule

### Requirement: Stay within the source's limits
The system SHALL stay within the request limits the source reports. When the source answers that the limit is
reached, it SHALL wait until the reported reset, then resume every watched panel.

#### Scenario: Throttled by the source
- **WHEN** the source answers "too many requests" with a reset in 5 seconds
- **THEN** no request is sent for 5 seconds
- **AND** afterwards every watched panel is collected again

### Requirement: One failing panel does not stop the others
A failed request SHALL be retried a configured number of times with growing, randomised waits. A panel that
keeps failing SHALL not delay the other panels, and the health status SHALL report it.

#### Scenario: One panel's question is broken
- **WHEN** one panel's question is rejected by the source on every try
- **THEN** all other panels keep being collected on time
- **AND** the health status names the failing panel

### Requirement: Read-only toward the source
The system SHALL only read from the source. It SHALL NOT create, change or delete anything there.

#### Scenario: A full day of collection
- **WHEN** the Collector runs for a day
- **THEN** nothing in the source has been created or changed by it

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

### Requirement: Late values are measured
When a poll returns a different value for a step that was already sent unmarked, the Collector SHALL count it per
source and remember how long after the step's end the change arrived; the Collector's health SHALL show the count
and the latest such delay, so the settle delay can be chosen from facts.

#### Scenario: A count completed after the settle delay
- **GIVEN** a settle delay of 30 seconds and a step sent unmarked with a count of 9
- **WHEN** a poll 50 seconds after the step ended returns 13 for it
- **THEN** the step is sent again with 13, and health shows one late change for that source, 50 seconds after the step

### Requirement: Each line says its panel's group
When describing a line, the Collector SHALL fill the group of each panel showing it: the dashboard group the panel is
in, or the section heading above it, empty when neither.

#### Scenario: Panel below a section heading
- **GIVEN** a top-level panel "Latencies" below the empty group "Upstream Service Metrics"
- **WHEN** its lines are described
- **THEN** their view says group "Upstream Service Metrics"

### Requirement: Where each panel sits is recorded
When the Collector reads a dashboard, it SHALL record for each panel the title of its group (empty when none), the
group's position on the dashboard and the panel's position in the group, counting groups and panels in the
dashboard's own order, including split groups. A changed order SHALL be sent again after the next re-read.

#### Scenario: Panels in groups
- **GIVEN** a dashboard with a panel outside any group, then the group "Upstream" with two panels
- **WHEN** the Collector reads it
- **THEN** the lone panel and the group get positions 0 and 1, and the group's panels positions 0 and 1 in it

#### Scenario: Panel moved in Datadog
- **WHEN** someone moves a panel to another group on a watched dashboard
- **THEN** after the next re-read its lines are described again with the new group and positions

### Requirement: A never-collected view is filled with its last 2 days first
A watched dashboard view with no line in the Store yet SHALL first get its last 2 days (a setting, at most the
retention) from Datadog at the live step, window by window, oldest first, sent marked as backfill; its live polling
SHALL start after them. A view already collected SHALL NOT be backfilled again, after a restart or an edit either.

#### Scenario: Dashboard added in the Settings tab
- **WHEN** a new dashboard is added for US
- **THEN** its last 2 days are sent oldest first, marked as backfill, and its live polling starts after them

#### Scenario: Restart
- **WHEN** the Collector restarts with dashboards it has collected before
- **THEN** nothing is backfilled

### Requirement: Backfill never starves live polling
Backfill calls SHALL go through the region's lane (its rate budget, 429 wait and calls in flight), batched like
polls, and SHALL use only capacity live polling leaves free, so other views keep their polling timing.

#### Scenario: Large dashboard
- **WHEN** a 270-question dashboard is backfilled
- **THEN** the other dashboards' polls keep their interval and no wave of 429 answers follows

### Requirement: Backfill progress is visible
The Collector's health SHALL list each view being backfilled with its progress (windows done of windows), and the
Settings tab SHALL show it per dashboard.

#### Scenario: Progress
- **WHEN** 6 of 12 windows of a view are done
- **THEN** health shows that view at 50%, and the Settings tab "pulling the last 2 days: 50%"
