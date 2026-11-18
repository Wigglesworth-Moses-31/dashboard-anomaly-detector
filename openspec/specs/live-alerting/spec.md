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

### Requirement: Each incident shows what and where
The page SHALL show for each incident its title, state, start, end or duration so far, and each affected line as a
path from the top: view (dashboard and saved view) → panel group → panel → the line's tags → its value against
normal with the unit and the change. Titles and tag values SHALL be shown in full, wrapped when long, never cut. The
page SHALL NOT show dashboard links on incident rows.

#### Scenario: Reading an incident on the page
- **WHEN** "/v1/invoices: latency up, errors up" is open
- **THEN** the page shows "latency up: 1,569 ms (normal 138 ms), Latencies" and "errors up: 8 (normal 0), Count"
  under their view and group, with no dashboard link

#### Scenario: A line in a group of a saved view
- **WHEN** an incident holds the line `status=200` of panel "Request Counts by Status Code" in group "API Gateway Metrics" of view "Shop · Payments", at 37 with normal 670
- **THEN** the page shows the path Shop · Payments → API Gateway Metrics → Request Counts by Status Code → status=200 → 37 (normal 670, −94%)

#### Scenario: Long values
- **WHEN** a line's `api` tag is 120 characters long
- **THEN** the page shows all 120 characters

### Requirement: The whole day stays on the page
The page SHALL keep every incident that started today, open, calm and closed. "Today" SHALL be the incident's start
date in the shared time zone setting (default UTC). At midnight in that zone, closed incidents of the day before
SHALL leave the page; open and calm incidents SHALL stay until they are closed.

#### Scenario: A busy day
- **WHEN** 37 incidents started today and 35 of them have closed
- **THEN** the page shows all 37: the 2 open ones first, then the 35 closed ones

#### Scenario: Midnight
- **WHEN** the clock in UTC passes midnight while one incident from 23:58 is still open
- **THEN** yesterday's closed incidents leave the page, and the open one stays until it is closed

#### Scenario: Calm from yesterday
- **WHEN** an incident from yesterday is calm without a reason at midnight
- **THEN** it stays on the page under "Needs a reason"

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

### Requirement: A line's past is one click away
Opening an affected line SHALL show the line's earlier incidents of the last 2 days: the last one before the current
one (start, duration, peak against normal, reason) and the list of all of them, newest first. A reason not given yet
SHALL show as "—". The history SHALL come back the same after a Notifier restart.

#### Scenario: Three times in two days
- **GIVEN** a line had incidents yesterday at 22:14 and today at 09:03
- **WHEN** its incident of 13:04 is opened on the page
- **THEN** it shows "last time: today 09:03" with its duration, peak and normal, and lists 3 incidents in the last 2 days

#### Scenario: After a restart
- **WHEN** the Notifier restarts and the same line is opened
- **THEN** the same history is shown

#### Scenario: Older than two days
- **WHEN** a line's only earlier incident was 3 days ago
- **THEN** the page shows "no earlier incident in the last 2 days"

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

### Requirement: Incidents waiting for a reason are listed
The page SHALL list calm incidents under "Needs a reason", with when they started and became calm, how long they
lasted, and their lines; the list SHALL show how many are waiting.

#### Scenario: Two waiting
- **WHEN** two incidents are calm without a reason
- **THEN** "Needs a reason (2)" lists both

### Requirement: A reason can be given on the page
Every line of an open or calm incident that is not BAU and has no reason SHALL have a reason box (1 to 500
characters), and the incident SHALL offer "same reason for all" for every such line. Sending SHALL show "saving…"
until the line's (or lines') reason comes back in the incident's next event, then the line shows its reason with the
time; when nothing comes back within 10 seconds, the page SHALL say "not saved" and keep the text. Empty and too long
reasons SHALL be refused on the page.

#### Scenario: Reason given
- **WHEN** a reason is typed for the 404 line of a calm incident and sent
- **THEN** within a second the line shows the reason with the time, in the display time zones, and the incident closes if no other line waits

#### Scenario: Nothing came back
- **WHEN** the Detector does not answer within 10 seconds
- **THEN** the page says "not saved" and the typed reason stays in the box

#### Scenario: Same reason for all
- **WHEN** "same reason for all" is used with one text on an incident with two lines waiting
- **THEN** both lines get that reason

### Requirement: Reasons show in a line's past
A line's history SHALL show that line's own reason for each earlier incident, "BAU" when it was business as usual,
or "—" when none was given.

#### Scenario: Earlier reason
- **WHEN** a line's earlier incident was closed with "deploy rolled back" on that line
- **THEN** that entry of the line's history shows "reason: deploy rolled back"

#### Scenario: Earlier BAU
- **WHEN** a "general" line's earlier incident was BAU
- **THEN** that entry shows "reason: BAU"

### Requirement: BAU lines are shown as such
Lines marked BAU SHALL be shown dimmed with "BAU" and no reason box; an incident of only BAU lines SHALL be shown
dimmed.

#### Scenario: Mixed incident on the page
- **WHEN** an incident has one errors line and five "general" lines
- **THEN** the five read "BAU", dimmed, and only the errors line has a reason box
