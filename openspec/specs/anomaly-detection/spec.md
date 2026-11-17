# anomaly-detection Specification

## Purpose
Learns what is normal for every metric line, spots unusual jumps within seconds of the numbers arriving, groups
jumps that belong to the same problem into one incident, and announces each incident as it opens, changes and closes.

## Requirements

### Requirement: Normal is learned from recent values
For each metric line, the normal value SHALL be the median of its recent finished values (default: the last 30
minutes, at most 2 days). A line SHALL NOT be judged until it has enough history (default 10 minutes). At start,
the Detector SHALL fill each line's history from the Store, so lines with stored history are judged at once.

#### Scenario: One spike does not change normal
- **WHEN** a line's last values are 140, 135, 138, 1500, 142 ms
- **THEN** its normal value is 140 ms, not the average of 411 ms

#### Scenario: A new line
- **WHEN** a line appears with 3 minutes of history
- **THEN** it is not judged yet, and the health page counts it as warming up

#### Scenario: Restart with stored history
- **WHEN** the Detector restarts and the Store holds the last hour of a line
- **THEN** that line is judged from its first new value

### Requirement: Every line on the watched dashboards is watched
Every metric line collected from the watched dashboards SHALL be judged by exactly one rule: the first rule in the
settings that matches it, or else the general rule. No line SHALL be left unwatched, whether it measures the
application's own APIs, the gateway, or an upstream service it calls. The health page SHALL show, per dashboard and
panel, how many lines each rule watches.

#### Scenario: The lab skeleton dashboard
- **WHEN** the Detector watches the lab skeleton dashboard
- **THEN** the gateway's request counts, success rate and latency, every API's request counts, success rate and
  latency, every upstream service's request counts, success rate and latency, and requests per partner and per
  client are each watched by a rule

#### Scenario: A new dashboard
- **WHEN** a dashboard is added to the Collector's settings
- **THEN** within a minute every one of its lines is watched by a rule, with the history the Collector fetched for
  the last 10 minutes, and no code changes or restarts are needed

#### Scenario: A new panel
- **WHEN** a panel with a metric no rule mentions is added to a watched dashboard
- **THEN** its lines are watched by the general rule from the next collection on

### Requirement: Rules come from settings, matched per line
Rules SHALL live in the Detector's settings, in order, matched against each line's metric, tags, query and panel
title, ignoring case. Each rule SHALL give the signal, the bad direction (up, down, both), how far from normal
counts (a factor, or points for percentages), the steps in a row and the smallest change. Changes SHALL take effect
without a restart.

#### Scenario: Default rules
- **WHEN** the Detector runs with the default settings
- **THEN** the rules, first match first, are:

| Signal | Matches | Bad direction | How far | Steps | Smallest change |
|---|---|---|---|---|---|
| Errors | request counts with a 4xx or 5xx status, gRPC status other than OK, metrics named `errors` | up | 3 times normal | 2 | 3 per step |
| Traffic | all other request, hit and call counts (including per partner, client, upstream and callee) | both | 3 times up, or down to a third, and 3 times the line's usual wobble | 2 | 5 per step |
| Success rate | percentages titled or computed as a success rate | down | 5 points below normal, and 3 times the line's usual wobble | 3 | - |
| Error rate | percentages titled or computed as an error rate | up | 5 points above normal, and 3 times the line's usual wobble | 3 | - |
| Latency | latency metrics (average, p50, p95, slowest calls) | up | 3 times normal | 2 | 50 ms (0.05 s for metrics in seconds) |
| Saturation | CPU and other usage percentages | up | 20 points above normal | 3 | - |
| General | anything else | both | far outside the line's usual wobble, and at least twice or half normal | 3 | - |

#### Scenario: Stricter latency rule
- **WHEN** an operator changes the latency rule from 2 steps in a row to 3
- **THEN** a latency jump lasting only 2 steps no longer opens an incident

#### Scenario: Title in different case
- **WHEN** a panel is titled "Success rate % per service" and the rule says "success rate"
- **THEN** its lines are watched by the success-rate rule

### Requirement: Spot a spike quickly
A spike SHALL be a value beyond its rule, in the bad direction, for the rule's number of steps in a row. The
incident SHALL be opened within 5 seconds of the deciding value arriving on Kafka, in 95% of cases.

#### Scenario: Latency jumps
- **GIVEN** a latency line whose normal is 138 ms
- **WHEN** two steps in a row show 1213 ms and 1194 ms
- **THEN** an incident naming that line is opened, within 5 seconds of the second value arriving

#### Scenario: One odd step
- **WHEN** a single step shows 1213 ms and the next is 140 ms
- **THEN** no incident is opened

### Requirement: Percentages are judged in points
Success rates, error rates and usage percentages SHALL be judged by how many percentage points they move from
normal, not by a factor, because a success rate cannot fall to a third without almost everything failing.

#### Scenario: Success rate falls
- **WHEN** a success rate whose normal is 99% shows 74% for 3 steps
- **THEN** an incident is opened (25 points down)

#### Scenario: Small wobble
- **WHEN** a success rate whose normal is 99% shows 97% for 5 steps
- **THEN** no incident is opened (2 points)

#### Scenario: Error rate rises
- **WHEN** a 5xx error rate whose normal is 1% shows 26% for 3 steps
- **THEN** an incident is opened

### Requirement: Traffic is watched both ways
Traffic lines SHALL alarm when they rise far above normal and when they fall far below it, so both a flood of
requests and an upstream that stopped sending are noticed.

#### Scenario: A partner stops sending
- **WHEN** requests from partner `globex` to `/v1/invoices`, normally 30 per step, show 8 for 2 steps
- **THEN** an incident is opened

#### Scenario: Flood
- **WHEN** a service's requests, normally 50 per step, show 200 for 2 steps
- **THEN** an incident is opened

### Requirement: Small numbers do not alarm
A value SHALL NOT count as a spike unless it also differs from normal by at least its rule's smallest change and,
where the rule says so, by a set multiple of the line's own usual wobble, so lines with tiny or noisy values (few
requests per step) do not alarm on chance.

#### Scenario: A low-traffic success rate
- **GIVEN** an API with about 7 requests per step, whose success rate wobbles by about 10 points
- **WHEN** it shows 85% for 3 steps where normal is 100%
- **THEN** no incident is opened (15 points is less than 3 times its usual wobble)

#### Scenario: From zero errors to one
- **GIVEN** an error count whose normal is 0
- **WHEN** two steps show 1 error each
- **THEN** no incident is opened

### Requirement: A line that goes silent
A line that normally has a value at every step (at least 9 in 10 steps over its history) SHALL alarm when it has no
value for a set time (default 1 minute) while other lines of the same dashboard keep arriving. Lines that are often
empty (for example a rare error code) SHALL NOT alarm on silence. If every line of a dashboard goes silent together,
that SHALL be shown on the health page as a collection problem, not as incidents.

#### Scenario: Upstream service stops answering
- **WHEN** an upstream service's request count, present every 10 seconds for an hour, has no value for 1 minute
  while the rest of the dashboard keeps arriving
- **THEN** an incident is opened saying the line went silent

#### Scenario: Rare error code
- **WHEN** a 503 count that appears only now and then has no value for 10 minutes
- **THEN** no incident is opened

#### Scenario: Collection stopped
- **WHEN** no line of a dashboard arrives for 1 minute
- **THEN** no incidents are opened, and the health page shows the dashboard as not arriving

### Requirement: Silence of errors is good news
Signals named in the settings (default: error counts and error rates) SHALL NOT be judged on silence, because an
error line that stops reporting means there are no errors.

#### Scenario: An error line stops reporting
- **WHEN** a 500-error count that had a value every step has none for 2 minutes while its panel continues
- **THEN** no incident is opened: fewer errors is not a problem

### Requirement: One incident per problem
Spikes on different lines that overlap in time and belong to the same group SHALL form one incident. The group
SHALL come from settings as an ordered list of tags, the first one a line has deciding (default: `api`, then
`upstream_service`, then `peer.rpc.service`, then `service`, then `resource_name`, otherwise the dashboard).

#### Scenario: Latency, success rate and errors of one API
- **WHEN** `/v1/invoices` latency, success rate and 5xx count spike in the same minute
- **THEN** one incident titled for `/v1/invoices` lists all three lines

#### Scenario: A new dashboard with a different grouping tag
- **GIVEN** a new dashboard "Payments platform" whose lines are tagged `endpoint` instead of `api`
- **WHEN** two of its endpoints spike at the same time
- **THEN** one incident titled "Payments platform" lists both lines, and the health page shows the dashboard's lines
  as grouped by dashboard
- **AND** after `endpoint` is added to the grouping list in the settings, without a restart, the next spikes on
  two endpoints open one incident per endpoint

#### Scenario: Two APIs at once
- **WHEN** `/v1/invoices` and `/v1/orders` spike at the same time
- **THEN** two incidents are opened, one per API

#### Scenario: Upstream service
- **WHEN** upstream service `payments` shows its success rate falling and its latency rising
- **THEN** one incident titled as upstream `payments` lists both lines

### Requirement: An incident says what and where
An incident SHALL record its start, its end, and for each line involved its signal, peak value, peak time, normal
value and panel. Its title SHALL name the group, the signals involved, and whether the group is an upstream service.

#### Scenario: Reading an incident
- **WHEN** upstream `payments` success rate falls to 74% (normal 99%) at 08:16:10
- **THEN** the incident is titled "upstream payments: success rate down" and shows 74%, 08:16:10, 99% and the panel

### Requirement: Incidents close only after a calm period
An incident SHALL close only after all its lines have been back to normal for a set time (default 2 minutes).
A line that spikes again before that SHALL keep the same incident open.

#### Scenario: Flapping line
- **WHEN** a line goes back to normal for 30 seconds and then spikes again
- **THEN** the same incident stays open and no second incident is opened

#### Scenario: Lines of one problem read out of order
- **WHEN** an incident closes on one line's values up to 08:27:20, and then another line of the same group reports a
  spike that started at 08:25:20
- **THEN** the closed incident is reopened with that line, and no second incident is opened

#### Scenario: Problem over
- **WHEN** all lines of an incident are normal for 2 minutes
- **THEN** the incident closes with its end time set to the first calm step

### Requirement: Unfinished steps are not judged
A value marked partial SHALL NOT be judged and SHALL NOT enter the history.

#### Scenario: Low value in a partial step
- **WHEN** the newest step is partial and shows half the normal value
- **THEN** no incident is opened because of it

### Requirement: Decisions use the time of the numbers
Every decision (history window, steps in a row, calm period) SHALL use the time of the values, never the clock of
the machine. A value received again SHALL NOT be counted twice.

#### Scenario: Recording replayed fast
- **WHEN** the 42-minute recording is replayed in 15 seconds, twice
- **THEN** every line makes the same decisions both times (the same spikes at the same data times, the same calm
  times)

#### Scenario: The same step twice
- **WHEN** the value for 08:16:10 of a line arrives twice
- **THEN** it counts as one step

### Requirement: Saved and announced together
Each change to an incident (opened, updated, closed) SHALL be saved in the database and announced on
`incident-events`; neither SHALL happen without the other, even across a crash. After a restart, open incidents
SHALL be loaded from the database and continue.

#### Scenario: Crash after saving
- **WHEN** the Detector stops right after saving an incident change and before announcing it
- **THEN** after restart the change is announced, and a reader that drops repeats by event id sees it once

#### Scenario: Restart during an incident
- **WHEN** the Detector restarts while an incident is open
- **THEN** the same incident continues and later closes, without a second one being opened

### Requirement: Storage stays small
Closed incidents SHALL be removed after a set time (default 2 days, at most 7). Each line's history SHALL use a
fixed amount of memory.

#### Scenario: Old incidents
- **WHEN** an incident closed 3 days ago
- **THEN** it is no longer stored

### Requirement: Finds the recorded problems
With default settings, the live run's values SHALL give exactly one incident per recorded `/v1/invoices` problem
once its lines have their minimum history, and the incidents not caused by the traffic generator SHALL be counted
and listed. With live traffic, each problem cycle of the generator SHALL give one incident per affected group.

#### Scenario: The live run's values
- **WHEN** the 42-minute live run, as the Store kept it, is judged in time order
- **THEN** `/v1/invoices` has one incident for each problem from 08:08 to 08:40 (the 08:00 problem starts 40 seconds
  after the recording, before any history), each listing its latency, and from 08:16 also its 500 errors
- **AND** `/v1/telemetry`, billing, orders, upstream billing-core, upstream telemetry-ingest and the gateway each
  have one incident per problem from 08:08 to 08:40

#### Scenario: Live traffic
- **WHEN** the Collector polls the lab dashboards live while the traffic generator runs
- **THEN** each judged problem cycle gives one incident for `/v1/invoices` listing its latency and errors, and every
  line of both dashboards is watched by a rule

### Requirement: Health shows how the Detector is doing
The Detector's health SHALL show the lines watched per rule, the lines warming up, the lines watched by the general
rule, dashboards not arriving, the open incidents, and how far behind Kafka it is.

#### Scenario: Open incident
- **WHEN** an incident is open
- **THEN** the health page lists its id, title and start time

### Requirement: Dashboard links open the right site
An incident's dashboard links SHALL open the dashboard on the site its line came from, for every Datadog site,
at the incident's time.

#### Scenario: EU incident
- **WHEN** an incident is opened for a line from site `datadoghq.eu`
- **THEN** its link starts with `https://app.datadoghq.eu/dashboard/`

#### Scenario: US1 and US3 incidents
- **WHEN** incidents are opened for lines from `datadoghq.com` and `us3.datadoghq.com`
- **THEN** their links start with `https://app.datadoghq.com/dashboard/` and `https://us3.datadoghq.com/dashboard/`

### Requirement: The site is named when there are several
When lines from more than one site are watched, an incident's title and each line's panel SHALL name the site by
its short label (US1, US3, US5, EU, AP1...), and lines from different sites SHALL never be grouped into one incident.

#### Scenario: Same API in both regions
- **GIVEN** sources `us` and `eu`, both with lines tagged `api:/v1/invoices`
- **WHEN** latency rises in both regions at the same time
- **THEN** two incidents open, titled "EU · /v1/invoices: latency up" and "US1 · /v1/invoices: latency up"

#### Scenario: One source only
- **WHEN** lines from only one site are watched
- **THEN** titles stay as before, without a source name
