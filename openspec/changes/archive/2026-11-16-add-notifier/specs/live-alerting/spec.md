# Spec Delta

## Purpose

Shows incidents live on a web page the moment the Detector announces them, so people see problems without watching
dashboards, and can jump straight to the right dashboard and time.

## ADDED Requirements

### Requirement: Incidents appear live
An open page SHALL show a new incident within 5 seconds of its event reaching Kafka, without reloading. Its updates
(lines joining, new peaks) and its close SHALL appear the same way.

#### Scenario: Incident opens
- **GIVEN** the page is open
- **WHEN** the Detector announces "/v1/invoices: latency up" at 17:04:10
- **THEN** the incident is on the page by 17:04:15

#### Scenario: Incident closes
- **WHEN** an open incident closes
- **THEN** the page shows it as closed within 5 seconds, with its end time

### Requirement: Catch up on connect
A page that connects or reconnects SHALL first receive every incident of the day, open and closed, then live
changes, with nothing missed in between.

#### Scenario: Laptop wakes up
- **WHEN** a page loses its connection for 10 minutes and reconnects
- **THEN** it shows every incident of the day, including the ones that opened and closed while it was away

### Requirement: One current state per incident
The Notifier SHALL keep one state per incident: the event with the highest sequence wins, an event whose id was
already seen is dropped, and an older event arriving late never replaces a newer one.

#### Scenario: Repeat after a Detector crash
- **WHEN** the same event arrives twice
- **THEN** pages receive it once

#### Scenario: Old event late
- **WHEN** sequence 7 (closed) has been received and sequence 5 (open) arrives afterwards
- **THEN** the incident stays closed

### Requirement: Knows the open incidents after a restart
At start, the Notifier SHALL read `incident-events` from the beginning of what Kafka keeps, so it knows every open
incident without asking the Detector or reading its database. Pages SHALL reconnect by themselves.

#### Scenario: Notifier restarted during an incident
- **WHEN** the Notifier restarts while "/v1/invoices: latency up" is open
- **THEN** the open page reconnects within 10 seconds and shows the incident again, once

### Requirement: Slow viewers never slow the system
A page that reads slowly or not at all SHALL NOT delay other pages or the Detector. It SHALL receive the newest state
of each incident when it reads again, never a backlog of older updates, and the Notifier's memory per page SHALL
stay bounded.

#### Scenario: One stalled browser tab
- **WHEN** one page reads nothing for a minute while an incident gets 20 updates
- **THEN** other pages get each update within 5 seconds, and the stalled page then gets that incident once, in its
  newest state

### Requirement: Each incident shows what and where
The page SHALL show for each incident its title, state, start, end or duration so far, and for each line its signal,
direction, peak against normal with the unit, and panel; and a link to each dashboard at the incident's time.

#### Scenario: Reading an incident on the page
- **WHEN** "/v1/invoices: latency up, errors up" is open
- **THEN** the page shows "latency up: 1,569 ms (normal 138 ms), Latencies" and "errors up: 8 (normal 0), Count",
  and a link that opens the lab dashboard around the incident's start

### Requirement: The whole day stays on the page
The page SHALL keep every incident that started today, open and closed. "Today" SHALL be the incident's start date
in the shared time zone setting (default Asia/Kolkata). At midnight in that zone, closed incidents of the day before
SHALL leave the page; incidents still open SHALL stay until they close.

#### Scenario: A busy day
- **WHEN** 37 incidents started today and 35 of them have closed
- **THEN** the page shows all 37: the 2 open ones first, then the 35 closed ones

#### Scenario: Midnight
- **WHEN** the clock in Asia/Kolkata passes midnight while one incident from 23:58 is still open
- **THEN** yesterday's closed incidents leave the page, and the open one stays until it closes

### Requirement: The time zone is a setting
The time zone SHALL come from the shared settings, so the page and the daily report agree. A change, for example to
`UTC` or `America/New_York`, SHALL take effect without a restart: the page then shows times in that zone and the day
boundary moves to that zone's midnight. An unknown time zone SHALL be rejected and the previous one kept.

#### Scenario: Switch to New York time
- **WHEN** the operator changes the shared time zone from Asia/Kolkata to America/New_York
- **THEN** within a minute open pages show times in New York time and keep the incidents of New York's today

#### Scenario: A typo in the zone
- **WHEN** the time zone is set to "Asia/Kolkatta"
- **THEN** the change is rejected and the page keeps using Asia/Kolkata

### Requirement: A demo can count by arrival
A setting SHALL let the day be counted by when the Notifier received each incident instead of when it started, so a
replay of an older recording shows as today.

#### Scenario: Replay of 15 November during 16 November
- **GIVEN** the day is counted by arrival
- **WHEN** the 15 November recording is replayed on 16 November
- **THEN** its incidents show on the page as today's

### Requirement: A long day stays readable
The page SHALL show a summary of the day (incidents, open, closed, the longest), open incidents in full, newest first,
then closed incidents as one line each, newest first, expandable to their lines; a count of today's incidents per
group; and a filter by text.

#### Scenario: Which API broke most today
- **WHEN** billing had 5 incidents today and /v1/firmware 1
- **THEN** the per-group counts show "billing 5" before "/v1/firmware 1"

#### Scenario: Find one incident
- **WHEN** the viewer types "telemetry" in the filter
- **THEN** only incidents whose title or lines mention telemetry are listed

### Requirement: The page stays fast and light on a busy day
With 2,000 incidents of the day on the page, the page SHALL show them within 1 second of connecting, apply a burst
of changes within one frame (16 ms), answer the filter within 100 ms after typing stops, and keep only the latest
state of each incident in memory (under 30 MB of script memory in total).

#### Scenario: A very busy day
- **GIVEN** a page holding 2,000 incidents of the day
- **WHEN** 50 changes arrive in the same second
- **THEN** they are on the page within one frame, and scrolling stays smooth

#### Scenario: Filter a busy day
- **WHEN** the viewer types "billing" on a page with 2,000 incidents
- **THEN** only billing incidents are listed within 100 ms after the last key

### Requirement: The page is clear at a glance
Each incident SHALL show how bad it is by a colour per signal and a meter of peak against normal (times normal, or
percentage points for percentages); a new incident SHALL be highlighted briefly when it appears; the page SHALL follow
the system's light or dark theme and its reduced-motion setting.

#### Scenario: Which problem is worse
- **WHEN** one open incident has latency at 11 times normal and another at 3 times
- **THEN** the first one's meter is visibly longer

### Requirement: Notify when a problem opens
When the viewer has switched it on, the page SHALL show a desktop notification and may play a short sound when a
new incident opens. It SHALL NOT notify for updates, closes, or incidents already open when the page connected.

#### Scenario: New problem while working in another window
- **GIVEN** notifications are switched on
- **WHEN** "upstream billing-core: latency up" opens
- **THEN** one desktop notification with that title appears

#### Scenario: Page opened during an incident
- **WHEN** the page connects while three incidents are open
- **THEN** it shows them without notifying

### Requirement: The page shows its connection
The page SHALL show whether it is connected, reconnecting or disconnected, and when the last change arrived.

#### Scenario: Notifier stopped
- **WHEN** the Notifier stops
- **THEN** the page shows "reconnecting" within 5 seconds and keeps showing the last known incidents

### Requirement: Broken events are set aside
An event that fails the shared contract checks SHALL NOT reach any page; it SHALL be counted and logged.

#### Scenario: Event without an incident
- **WHEN** an event without its incident arrives
- **THEN** no page receives it and the count of set-aside events goes up by one

### Requirement: Health shows how the Notifier is doing
The Notifier's health SHALL show the pages connected, today's incidents (open and closed), the day and time zone in
use, changes received, updates merged for slow pages, events set aside, and how long ago the last change arrived.

#### Scenario: Two pages open
- **WHEN** two pages are connected
- **THEN** the health page shows 2 viewers
