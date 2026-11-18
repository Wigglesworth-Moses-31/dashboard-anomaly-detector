# Spec Delta

## Purpose

Shows each region's watched dashboards as live line charts in Datadog's group order, each panel and each metric on
its own page with its anomalies, light on memory however many dashboards there are. Replaces the Incidents page.

## ADDED Requirements

### Requirement: One page per region lists its dashboards
The page SHALL have one page per region (US, EU) listing that region's watched dashboards by title, each collapsed or
expanded (remembered per browser, collapsed at first). A collapsed dashboard SHALL show its number of panels and how
many of its metrics have open anomalies and how many wait for a reason. A dashboard added in Settings SHALL appear
without a reload.

#### Scenario: Two regions
- **GIVEN** US with 3 dashboards and EU with 2
- **WHEN** the US page is opened
- **THEN** it lists the 3 US dashboards collapsed, each with its panel count and badges, and the EU page lists the other 2

#### Scenario: A badge
- **WHEN** a metric of the "Shop" dashboard has an open anomaly and another waits for a reason
- **THEN** the collapsed "Shop" row shows "● 1 open" and "● 1 needs a reason"

### Requirement: Panels follow Datadog's groups and order
An expanded dashboard SHALL show its panels under their Datadog group headings, groups and panels in Datadog's order,
each group collapsible, in a grid that also fits a narrow screen. Exact sizes and positions SHALL NOT be copied.
Panels without metric questions SHALL NOT be shown.

#### Scenario: Grouped dashboard
- **WHEN** a dashboard with the groups "API Gateway Metrics" and "API Metrics" is expanded
- **THEN** the groups appear in that order with their panels in Datadog's order

### Requirement: Panels are live line charts
Each panel SHALL be a line chart of its lines (every widget type drawn as lines over time), with the unit and each
line's newest value, showing at most a set number of lines (default 20, the highest in the range) with "+ n more".
At the live ranges a panel on screen SHALL gain each new 10-second step within 5 seconds of it reaching Kafka, without
polling.

#### Scenario: New step arrives
- **WHEN** a new 10-second value of a shown line arrives
- **THEN** that panel's chart moves on by one step within 5 seconds

#### Scenario: Many lines
- **WHEN** a panel has 300 partner lines
- **THEN** it shows the 20 highest with "+ 280 more", and its Panel page shows all

### Requirement: A time range can be chosen
The region, panel and metric pages SHALL offer 15m and 1h (10-second values, moving live) and 4h, 1d and 7d
(per-minute values, labelled "per minute"). A range longer than the data kept SHALL show what is kept and say so.

#### Scenario: Long range
- **WHEN** 7d is chosen while 2 days are kept
- **THEN** the charts show 2 days of per-minute values and say "2 days kept"

### Requirement: Anomalies are shown on the charts
Each anomaly of a shown line SHALL be shaded over its time on every chart of that line; hovering the shading SHALL
name it. A panel with an open anomaly SHALL have a red edge; BAU anomalies SHALL be shaded grey.

#### Scenario: Incident on a shown line
- **WHEN** `/v1/invoices` latency had an anomaly from 10:02 to 10:09
- **THEN** the Latencies panel is shaded from 10:02 to 10:09, and hovering it names the anomaly

### Requirement: A panel opens on its own page
Clicking a panel SHALL open its Panel page: the panel large, all its lines, a legend with each line's tags and newest
value that can be sorted and searched, the shaded anomalies, and the range picker. Clicking a line SHALL open its
Metric page.

#### Scenario: Open a panel
- **WHEN** the panel "Request Counts by Status Code" is clicked
- **THEN** its page shows every status line with its newest value, and clicking `status=500` opens that metric's page

### Requirement: A metric's page shows its graph and details
The Metric page SHALL show at the top the line's graph with the range picker and shaded anomalies, and its details:
metric and question, tags, dashboard › group › panel, region, unit, kind, the rule that judges it, its normal value
now, its newest value and when it arrived.

#### Scenario: Open a metric
- **WHEN** the `status=500` line is opened
- **THEN** its graph and details are at the top of its page

### Requirement: A metric's page lists its anomalies
Below the graph, the Metric page SHALL list the line's anomalies of the kept days, newest first: when it started (in
every display zone), how long, peak against normal (a drop as a percentage), direction, state (open, needs a reason,
closed), and its reason: the line's own, "BAU", or "—" when none was given. It SHALL come back the same after a
Notifier restart.

#### Scenario: Three times
- **GIVEN** a line had anomalies yesterday at 22:14 and today at 09:03 and 13:04
- **WHEN** its page is opened
- **THEN** the three are listed newest first, each with its duration, peak and normal, state and reason

#### Scenario: A drop
- **WHEN** a traffic line dropped to 0
- **THEN** its anomaly reads "dropped to 0 (−100%)"

### Requirement: A reason can be given on the metric page
An anomaly of the line that is open or calm, not BAU and without a reason SHALL have a reason box (1 to 500
characters) on the Metric page, and "same reason for all n lines of this incident", with links to those lines.
Sending SHALL show "saving…" until the Detector's answer arrives, and "not saved" with the reason when it fails. BAU
anomalies SHALL be shown dimmed with "BAU" and no reason box.

#### Scenario: One line
- **WHEN** "bad deploy" is sent for the waiting anomaly on the `status=500` page
- **THEN** "saving…" shows, then the reason with its time

#### Scenario: All lines
- **WHEN** "gateway restart" is sent as the same reason for all 3 lines of the incident
- **THEN** all three lines carry it and the incident closes

### Requirement: Waiting anomalies are always one click away
The header SHALL show how many metrics have open anomalies and how many wait for a reason; clicking it SHALL list
those metrics (region, dashboard › panel, line, since when), each linking to its Metric page. It SHALL update live.

#### Scenario: Needs a reason
- **WHEN** two metrics wait for a reason
- **THEN** the header shows "● 2 need a reason" and its list links to both metric pages

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
`notifier.yml`, changed without a restart; a range longer than 7 days SHALL be refused.

#### Scenario: Range beyond what is kept
- **WHEN** a 30-day range is added to the settings
- **THEN** the setting is refused and the previous ranges stay

### Requirement: Health shows how the dashboard pages are doing
The Notifier's health SHALL show the pages open, the panels on screen across them, live values pushed, Store calls
and their slowest answer.

#### Scenario: After opening a dashboard
- **WHEN** a dashboard has been expanded on one page
- **THEN** health shows one page, its panels on screen and one Store call with its time
