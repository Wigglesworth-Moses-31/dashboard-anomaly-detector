# dashboard-view Specification

## Purpose
Shows each region's watched dashboards as live line charts in Datadog's group order, each panel and each metric on
its own page with its anomalies, light on memory however many dashboards there are. Replaces the Incidents page.

## Requirements

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

### Requirement: Short spikes stay visible
Each line SHALL reach the browser thinned to at most a set number of points (default 600): for each pixel column,
the lowest and highest value, never an average, so a single 10-second spike shows at every range.

#### Scenario: One spike in two days
- **WHEN** a line has one 10-second spike among two calm days and 7d is shown
- **THEN** the spike is visible

### Requirement: Only what is on screen costs memory
A chart SHALL be created only when its panel comes on screen and released when its dashboard is collapsed or it is
far off screen; live values SHALL be sent only for the panels a page shows, the newest value winning for a slow page.
With every dashboard of a region expanded and scrolled through (20 dashboards × 40 panels), the page SHALL stay under
200 MB of browser memory.

#### Scenario: Collapsed dashboards
- **WHEN** 19 of 20 dashboards are collapsed
- **THEN** no chart exists for them and none of their values are sent to the page

#### Scenario: Large region
- **WHEN** a test region of 20 dashboards × 40 panels × 10 lines is expanded and scrolled through
- **THEN** browser memory stays under 200 MB and the measured figure is written down

### Requirement: Opening a dashboard is cheap
Expanding a dashboard SHALL ask the Store once for all its lines and the range, and show its panels within 1 second
for 40 panels and 200 lines over 1 hour.

#### Scenario: Large dashboard
- **WHEN** a 40-panel dashboard is expanded
- **THEN** one Store call is made and the panels show within 1 second

### Requirement: The pages fail on their own
When the Store cannot be reached, charts SHALL say "history unavailable" while live values, anomalies and reasons keep
working.

#### Scenario: Store stopped
- **WHEN** the Store is stopped and a dashboard is expanded
- **THEN** its panels say "history unavailable" and the attention counter still updates

### Requirement: The page says how its panels differ from Datadog
The pages SHALL say in one line that each 10-second step shows its highest value, so peaks can be higher than on
Datadog's own graphs.

#### Scenario: Comparing with Datadog
- **WHEN** a panel's peak is higher than on Datadog
- **THEN** the line under the range picker explains why

### Requirement: Dashboard view settings live outside the code
The ranges offered, the default range, the most points per line and the most lines per panel SHALL be settings in
`notifier.yml`, changed without a restart; a range longer than 10 days SHALL be refused.

#### Scenario: Range beyond what is kept
- **WHEN** a 30-day range is added to the settings
- **THEN** the setting is refused and the previous ranges stay

### Requirement: Health shows how the dashboard pages are doing
The Notifier's health SHALL show the pages open, the panels on screen across them, live values pushed, Store calls
and their slowest answer.

#### Scenario: After opening a dashboard
- **WHEN** a dashboard has been expanded on one page
- **THEN** health shows one page, its panels on screen and one Store call with its time

### Requirement: Past anomalies are shown as such
An anomaly found in backfill SHALL be listed in its metric's side panel as "past · found in backfill" with its times,
shaded grey on graphs, never counted as open or waiting, and SHALL NOT notify.

#### Scenario: Metric page
- **WHEN** a line's side panel is opened after its backfill found a spike
- **THEN** the spike is listed as "past · found in backfill", closed, with no reason box

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
