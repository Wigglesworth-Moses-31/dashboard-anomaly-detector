# live-alerting Specification

## Purpose
Shows incidents live on a web page the moment the Detector announces them, so people see problems without watching
dashboards, and can jump straight to the right dashboard and time.

## Requirements

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

### Requirement: The time zone is a setting
The time zone SHALL come from the shared settings, so the page and the daily report agree. A change, for example to
`UTC` or `America/New_York`, SHALL take effect without a restart: the day boundary moves to that zone's midnight.
Times themselves SHALL be shown in the display time zones. An unknown time zone SHALL be rejected and the previous one
kept.

#### Scenario: Switch to New York time
- **WHEN** the operator changes the shared time zone from Asia/Kolkata to America/New_York
- **THEN** within a minute open pages keep the incidents of New York's today

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

### Requirement: Notify when a problem opens
When the viewer has switched it on, the page SHALL show a desktop notification and may play a short sound when a
new incident opens that has at least one line that is not BAU. It SHALL NOT notify for updates, closes, incidents of
only BAU lines, or incidents already open when the page connected.

#### Scenario: New problem while working in another window
- **GIVEN** notifications are switched on
- **WHEN** "upstream billing-core: latency up" opens
- **THEN** one desktop notification with that title appears

#### Scenario: Page opened during an incident
- **WHEN** the page connects while three incidents are open
- **THEN** it shows them without notifying

#### Scenario: Only BAU
- **WHEN** an incident of only "general up" lines opens
- **THEN** no notification appears

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

### Requirement: Drops read as percentages
For a line below normal, the page SHALL show how far it fell as a percentage of normal ("−92%"), and a value of 0
as "dropped to 0 (−100%)", never a ratio divided by zero.

#### Scenario: Drop to zero
- **WHEN** an incident line has value 0 and normal 5
- **THEN** the page shows "dropped to 0 (−100%)"

#### Scenario: Partial drop
- **WHEN** an incident line has value 1 and normal 13
- **THEN** the page shows "−92%"

### Requirement: Every time on the page in all display zones
Every time the page shows (incident start and end, "since", the closed list, line history, reasons) SHALL appear in
each display zone, the main one first, each with its short name for that date (EST or EDT); a zone on another calendar
day SHALL show that date.

#### Scenario: Winter
- **WHEN** an incident starts at 13:04:10 UTC on 17 Nov 2026
- **THEN** the page shows `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`

#### Scenario: Summer
- **WHEN** an incident starts at 13:04:10 UTC on 17 Jul 2026
- **THEN** New York is shown as `09:04:10 EDT`

#### Scenario: Another calendar day
- **WHEN** an incident starts at 23:10 UTC on 17 Nov 2026
- **THEN** India is shown as `04:40 IST (18 Nov)`
