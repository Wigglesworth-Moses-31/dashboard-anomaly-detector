## ADDED Requirements

### Requirement: Slow signals are judged on 5-minute averages
A rule with a judging time longer than one step SHALL judge its lines on averages: the average of a line's values in
each period on the clock (default 5 minutes: 10:00–10:05, 10:05–10:10 …), judged once the period is complete and
settled. Its normal SHALL be the median of the line's averages over the slow history (default 2 hours, 24 averages),
and one average beyond the rule SHALL be enough. Rules judged every step SHALL work as before.

#### Scenario: A 30-second dip
- **GIVEN** a success rate judged every 5 minutes, normally 99%
- **WHEN** it shows 60% for 30 seconds and 99% for the rest of 10:00–10:05
- **THEN** the average is about 95% (4 points down) and no incident is opened

#### Scenario: A dip that lasts
- **WHEN** the same success rate shows 60% from 10:00 to 10:05
- **THEN** one incident is opened once the 10:00–10:05 average is complete and settled, starting at 10:00

#### Scenario: Errors stay fast
- **WHEN** a 500-error count, judged every step, jumps for 30 seconds
- **THEN** an incident is opened within 5 seconds of the deciding value, as before

### Requirement: A slow line needs enough averages
A line of a slow rule SHALL NOT be judged until it has a set history of averages (default 30 minutes, 6 averages). At
start, its averages SHALL be filled from the Store's stored values, so a line with 2 hours stored is judged at once.

#### Scenario: Restart
- **WHEN** the Detector restarts and the Store holds the last 3 hours of a latency line
- **THEN** that line has its 24 averages and is judged at the end of the next 5 minutes

## MODIFIED Requirements

### Requirement: Rules come from settings, matched per line
Rules SHALL live in the Detector's settings, in order, matched against each line's metric, tags, query and panel
title, ignoring case. Each rule SHALL give the signal, the bad direction (up, down, both), how far from normal
counts (a factor, or points for percentages), the steps in a row, the smallest change and how often it is judged
(every step, or on averages of a longer period). Changes SHALL take effect without a restart.

#### Scenario: Default rules
- **WHEN** the Detector runs with the default settings
- **THEN** the rules, first match first, are:

| Signal | Matches | Bad direction | How far | Judged | Steps | Smallest change |
|---|---|---|---|---|---|---|
| Success rate | percentages titled or computed as a success rate | down | 5 points below normal, and 3 times the line's usual wobble | 5-minute averages | 1 | - |
| Error rate | percentages titled or computed as an error rate | up | 5 points above normal, and 3 times the line's usual wobble | every step | 3 | - |
| Errors | request counts with a 4xx or 5xx status, gRPC status other than OK, metrics named `errors` | up | 3 times normal | every step | 2 | 3 per step |
| Latency | latency metrics (average, p50, p95, slowest calls) | up | 3 times normal | 5-minute averages | 1 | 50 ms (0.05 s for metrics in seconds) |
| Saturation | CPU and other usage percentages | up | 20 points above normal | 5-minute averages | 1 | - |
| Traffic | all other request, hit and call counts (including per partner, client, upstream and callee) | both | 3 times up, or down to a third, and 3 times the line's usual wobble | 5-minute averages | 1 | 5 per step |
| General | anything else | both | far outside the line's usual wobble, and at least twice or half normal | 5-minute averages | 1 | - |

#### Scenario: Stricter latency rule
- **WHEN** an operator changes the latency rule from 1 average to 2 in a row
- **THEN** a latency jump lasting only one 5-minute average no longer opens an incident

#### Scenario: Title in different case
- **WHEN** a panel is titled "Success rate % per service" and the rule says "success rate"
- **THEN** its lines are watched by the success-rate rule

### Requirement: Spot a spike quickly
A spike SHALL be a value beyond its rule, in the bad direction, for the rule's number of steps (or averages) in a
row. The incident SHALL be opened within 5 seconds of the deciding value arriving on Kafka, or for a slow rule of its
period being complete and settled, in 95% of cases.

#### Scenario: Latency jumps
- **GIVEN** a latency line whose normal 5-minute average is 138 ms
- **WHEN** its 10:00–10:05 values average 1200 ms
- **THEN** an incident naming that line is opened, within 5 seconds of that average being complete and settled

#### Scenario: One odd step
- **WHEN** a single 10-second step shows 1213 ms and the rest of its 5 minutes show 140 ms
- **THEN** no incident is opened (the average is about 175 ms)

### Requirement: Percentages are judged in points
Success rates, error rates and usage percentages SHALL be judged by how many percentage points they move from
normal, not by a factor, because a success rate cannot fall to a third without almost everything failing.

#### Scenario: Success rate falls
- **WHEN** a success rate whose normal is 99% averages 74% over 5 minutes
- **THEN** an incident is opened (25 points down)

#### Scenario: Small wobble
- **WHEN** a success rate whose normal is 99% averages 97% over 5 minutes
- **THEN** no incident is opened (2 points)

#### Scenario: Error rate rises
- **WHEN** a 5xx error rate whose normal is 1% shows 26% for 3 steps
- **THEN** an incident is opened within 5 seconds of the third step

### Requirement: Traffic is watched both ways
Traffic lines SHALL alarm when their average rises far above normal and when it falls far below it, so both a flood
of requests and an upstream that stopped sending are noticed.

#### Scenario: A partner stops sending
- **WHEN** requests from partner `globex` to `/v1/invoices`, normally 30 per step, average 8 per step over 5 minutes
- **THEN** an incident is opened

#### Scenario: Flood
- **WHEN** a service's requests, normally 50 per step, average 200 per step over 5 minutes
- **THEN** an incident is opened

### Requirement: Small numbers do not alarm
A value SHALL NOT count as a spike unless it also differs from normal by at least its rule's smallest change and,
where the rule says so, by a set multiple of the line's own usual wobble, so lines with tiny or noisy values (few
requests per step) do not alarm on chance.

#### Scenario: A low-traffic success rate
- **GIVEN** an API with about 7 requests per step, whose 5-minute success rate wobbles by about 10 points
- **WHEN** it averages 85% over 5 minutes where normal is 100%
- **THEN** no incident is opened (15 points is less than 3 times its usual wobble)

#### Scenario: From zero errors to one
- **GIVEN** an error count whose normal is 0
- **WHEN** two steps show 1 error each
- **THEN** no incident is opened

### Requirement: Incidents close only after a calm period
An incident SHALL become calm, not closed, once all its lines are back to normal: a line judged every step after a
set time (default 2 minutes), a line judged on averages after one average within its rule. Its end time SHALL be the
first calm step or period. A line that spikes again before that SHALL keep the same incident open, and a spike of its
group while it is calm SHALL make it open again, the same incident.

#### Scenario: Flapping line
- **WHEN** an error line goes back to normal for 30 seconds and then spikes again
- **THEN** the same incident stays open and no second incident is opened

#### Scenario: Lines of one problem read out of order
- **WHEN** an incident becomes calm on one line's values up to 08:27:20, and then another line of the same group
  reports a spike that started at 08:25:20
- **THEN** the incident is open again with that line, and no second incident is opened

#### Scenario: Problem over
- **WHEN** an error line is normal for 2 minutes, and the latency line of the same incident has a 5-minute average within its rule
- **THEN** the incident becomes calm with its end time set to the first calm step or period, and is not closed

#### Scenario: Calm, then it happens again
- **GIVEN** an incident that became calm an hour ago and has no reason yet
- **WHEN** a line of its group spikes again
- **THEN** the same incident is open again; no new incident is opened

### Requirement: Unfinished steps are not judged
A value marked partial SHALL NOT be judged and SHALL NOT enter the history. A period of a slow rule SHALL NOT be
judged before it is complete and settled; partial values SHALL NOT enter its average.

#### Scenario: Low value in a partial step
- **WHEN** the newest step is partial and shows half the normal value
- **THEN** no incident is opened because of it

#### Scenario: Period not complete
- **WHEN** at 10:03 a latency line has averaged 3 times normal since 10:00
- **THEN** nothing is decided until 10:05 plus the settle delay

### Requirement: Finds the recorded problems
With default settings, the live run's values SHALL give one incident per recorded `/v1/invoices` problem once its
lines have their minimum history, and the incidents not caused by the traffic generator SHALL be counted and listed.
With live traffic, each problem cycle of the generator SHALL give one incident per affected group.

#### Scenario: The live run's values
- **WHEN** the 42-minute live run, as the Store kept it, is judged in time order
- **THEN** `/v1/invoices` has one incident for each problem from 08:16 to 08:40 listing its 500 errors, and its latency
  where the problem raises a 5-minute average beyond the latency rule
- **AND** the number of incidents of other groups is written down, and is not more than before this change

#### Scenario: Live traffic
- **WHEN** the Collector polls the lab dashboards live while the traffic generator runs
- **THEN** each judged problem cycle gives one incident for `/v1/invoices` listing its errors, and every line of both
  dashboards is watched by a rule
