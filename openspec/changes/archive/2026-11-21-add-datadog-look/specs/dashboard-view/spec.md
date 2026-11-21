# Spec Delta

## REMOVED Requirements

### Requirement: One page per region lists its dashboards
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Replaced by the left navigation and the dashboard list ("Navigation like Datadog").
**Migration**: None; the data behind the pages is unchanged.

### Requirement: Panels follow Datadog's groups and order
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Replaced by "Dashboards are laid out as in their JSON".
**Migration**: None; the data behind the pages is unchanged.

### Requirement: Panels are live line charts
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Replaced by "Widgets are drawn by their type".
**Migration**: None; the data behind the pages is unchanged.

### Requirement: A panel opens on its own page
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Replaced by "A widget opens full screen".
**Migration**: None; the data behind the pages is unchanged.

### Requirement: A metric's page shows its graph and details
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Replaced by "A metric opens in a side panel".
**Migration**: None; the data behind the pages is unchanged.

### Requirement: A metric's page lists its anomalies
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Part of "A metric opens in a side panel".
**Migration**: None; the data behind the pages is unchanged.

### Requirement: A reason can be given on the metric page
**Reason**: The pages are rebuilt to look and work like Datadog (user, 21 Nov 2026). Moved to the side panel ("A reason can be given in the side panel").
**Migration**: None; the data behind the pages is unchanged.

## MODIFIED Requirements

### Requirement: A time range can be chosen
Each dashboard and each full-screen widget SHALL offer Past 15 Minutes, 30 Minutes, 1 Hour, 3 Hours, 6 Hours,
12 Hours and 1 Day, all moving live with a pause and resume control. A range longer than the data kept SHALL show what
is kept and say so.

#### Scenario: Long range
- **WHEN** Past 1 Day is chosen while 6 hours are kept
- **THEN** the graphs show 6 hours and say "6 hours kept"

### Requirement: Anomalies are shown on the charts
Each anomaly of a line on a timeseries SHALL be shown as a red band over its time; hovering it SHALL name it; past
("found in backfill") and BAU anomalies SHALL be grey. A widget with an open anomaly SHALL have a red edge, and a
query value whose line has one SHALL be shown in red.

#### Scenario: Incident on a shown line
- **WHEN** `/v1/invoices` latency had an anomaly from 10:02 to 10:09
- **THEN** the Latencies widget shows a red band from 10:02 to 10:09, and hovering it names the anomaly

### Requirement: Waiting anomalies are always one click away
The left navigation SHALL show how many metrics have open anomalies and how many wait for a reason; clicking it SHALL
list those metrics (region, dashboard › widget, line, since when), each opening its side panel on its dashboard. It
SHALL update live.

#### Scenario: Needs a reason
- **WHEN** two metrics wait for a reason
- **THEN** the navigation shows "2 need a reason" and its list opens each metric's side panel

### Requirement: Past anomalies are shown as such
An anomaly found in backfill SHALL be listed in its metric's side panel as "past · found in backfill" with its times,
shaded grey on graphs, never counted as open or waiting, and SHALL NOT notify.

#### Scenario: Metric page
- **WHEN** a line's side panel is opened after its backfill found a spike
- **THEN** the spike is listed as "past · found in backfill", closed, with no reason box

## ADDED Requirements

### Requirement: Graphs use an auto interval like Datadog
Each graph SHALL roll up its 10-second values to the smallest of 10 s, 20 s, 30 s, 1 m, 2 m and 5 m that gives at
most 300 points over the range, with Datadog's default method (average; sum for counts shown as counts) unless the
widget's query names its own rollup, and SHALL show the interval in use.

#### Scenario: Past 6 Hours
- **WHEN** a latency graph is shown for Past 6 Hours
- **THEN** each point is the average of its 2-minute interval and the graph says "2m interval"

#### Scenario: Counts
- **WHEN** a request count shown as counts is shown for Past 1 Hour
- **THEN** each point is the sum of its 20-second interval

### Requirement: Every range moves live
At 15 and 30 minutes a graph SHALL take each new 10-second value as it arrives; at longer ranges it SHALL refresh at
its interval and at least every 30 seconds. Pausing SHALL freeze every graph of the dashboard.

#### Scenario: Past 6 Hours live
- **WHEN** Past 6 Hours is shown for 4 minutes
- **THEN** the graphs have moved on by two 2-minute points

### Requirement: Navigation like Datadog
A slim left navigation SHALL offer Dashboards, one entry per region, Settings and the attention counter. Each region
SHALL have a searchable list of its watched dashboards with the number of widgets and badges for open and waiting
anomalies; opening one SHALL show the dashboard.

#### Scenario: Dashboard list
- **WHEN** US is chosen and "shop" is typed in the search
- **THEN** only US dashboards whose title contains "shop" are listed, each with its widgets and badges

### Requirement: Dashboards are laid out as in their JSON
A dashboard SHALL be drawn on a 12-column grid with each widget at the position and size given in its exported JSON;
a dashboard without positions SHALL flow its widgets in order like Datadog's automatic layout. Group widgets SHALL be
boxes with their title and background colour, collapsible, holding their widgets.

#### Scenario: Ordered layout
- **WHEN** the lab dashboard, whose first group sits at x 0, y 0, width 12, height 4, is opened
- **THEN** that group spans the full width at the top, with its widgets at their positions inside it

#### Scenario: Automatic layout
- **WHEN** a dashboard without positions is opened
- **THEN** its widgets flow in their order, groups full width

### Requirement: The top bar shows the dashboard's variables and time
A dashboard's top bar SHALL show its title, its template variables as chips with the values in use (from the file's
defaults and the settings' filters, read-only) and the time picker.

#### Scenario: Variables
- **WHEN** a dashboard with `$env` set to `prod` by the settings is opened
- **THEN** a chip reads "env: prod"

### Requirement: Widgets are drawn by their type
Timeseries SHALL be drawn as lines, bars or areas as configured, with their palette, legend and y-axis settings; a
query value SHALL be a big number with its unit, precision and conditional colours and a faint trend; a top list SHALL
be horizontal bars, highest first.

#### Scenario: Query value
- **WHEN** a query value widget with precision 1 and a red conditional format above 500 shows 612.34 ms
- **THEN** it reads "612.3 ms" in red

#### Scenario: Bars
- **WHEN** a timeseries is configured to display bars
- **THEN** it is drawn as bars

### Requirement: Tables, rings, splits and notes are drawn too
A query table SHALL be a sortable table of latest values; a sunburst SHALL be a ring of latest values; a split group
SHALL be one small graph per value of its split tag; a note SHALL show its text. A widget with no metric question
SHALL be a grey box saying it is not collected.

#### Scenario: Service map
- **WHEN** a dashboard holds a service map
- **THEN** its place shows a grey box "not collected by this system"

#### Scenario: Split group
- **WHEN** a split group splits latency by `service` with three services
- **THEN** three small graphs are shown, one per service

### Requirement: Hovering shows the same moment everywhere
Hovering a timeseries SHALL show a vertical line at that moment on every timeseries of the dashboard, each with a
tooltip of its values then.

#### Scenario: Shared crosshair
- **WHEN** the pointer is at 10:04 on the Latencies widget
- **THEN** every other timeseries on the dashboard shows a line at 10:04

### Requirement: A widget opens full screen
Every widget SHALL open full screen, with its graph large and a legend table of each line's min, max, average and
latest value, sortable and searchable.

#### Scenario: Full screen
- **WHEN** "Request Counts by Status Code" is opened full screen
- **THEN** every status line is listed with min, max, average and latest value

### Requirement: A metric opens in a side panel
Clicking a line or an anomaly band SHALL open a side panel over the dashboard with the line's details (metric and
question, tags, dashboard › group › widget, region, unit, kind, signal, normal at its last anomaly, newest value) and
its anomalies of the kept time, newest first, each with when (every display zone), how long, peak against normal (a
drop as a percentage), direction, state and reason.

#### Scenario: Open from a band
- **WHEN** the red band on the Latencies widget is clicked
- **THEN** the side panel shows that line's details and the anomaly at the top of its list

### Requirement: A reason can be given in the side panel
An anomaly of the line that is open or calm, not BAU and without a reason SHALL have a reason box (1 to 500
characters) in the side panel, and "same reason for all n lines of this anomaly" with links that open those lines'
panels. Sending SHALL show "saving…" until the Detector's answer arrives, and "not saved" with the reason when it
fails. BAU anomalies SHALL be dimmed with "BAU" and no reason box.

#### Scenario: One line
- **WHEN** "bad deploy" is sent for the waiting anomaly in the `status=500` side panel
- **THEN** "saving…" shows, then the reason with its time

### Requirement: Light or dark like the computer
The page SHALL follow the computer's light or dark setting, both styled like Datadog's themes.

#### Scenario: Dark mode
- **WHEN** the Mac is in dark mode
- **THEN** the page is dark, its graphs readable
