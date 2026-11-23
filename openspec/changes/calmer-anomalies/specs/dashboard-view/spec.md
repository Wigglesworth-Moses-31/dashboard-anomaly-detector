## ADDED Requirements

### Requirement: The page looks like the Incidents page
The page SHALL keep the look of the Incidents page of 16-18 Nov 2026: its colours in light and dark, a top bar, the
day's cards (today, open, need a reason, closed, longest), and sections Open, Needs a reason and Closed today. Each
anomaly SHALL be a card with a coloured left edge (red open, amber needs a reason, grey closed) showing its graph,
signal, line, peak, normal, a bar of how far off, its time and how it was judged ("5-min average" or "every 10 s").

#### Scenario: A quiet morning
- **WHEN** there was one closed anomaly today and nothing is open
- **THEN** the cards read 1 today, 0 open, 0 need a reason, 1 closed, and the Open section says "All quiet today ✓"

#### Scenario: Closed today
- **WHEN** five anomalies closed today
- **THEN** Closed today lists them as compact rows with their times and reasons, a filter box and one chip per region › dashboard, BAU rows dimmed

### Requirement: Only graphs with anomalies are drawn
The page SHALL draw no whole dashboards. Unfolding an anomaly's card SHALL draw the exact graph it happened on, a whole widget
or one small graph of a split group, with all its lines, the anomaly's time marked and its lines bold. Under it,
folded, SHALL be the other graphs of the same panel and, for an upstream, its callers' graphs from `upstreams.yml`.

#### Scenario: One small graph
- **GIVEN** an open anomaly on `api=/v1/invoices` in the "Success Rates" split group of "Shop", US
- **WHEN** its card is unfolded
- **THEN** only the `/v1/invoices` success-rate graph is drawn, with all its lines, the anomaly's band and its line bold, and "Other graphs of Success Rates" folded under it

#### Scenario: Upstream
- **WHEN** an anomaly on `upstream_service=billing-core` latency is opened and `upstreams.yml` lists `/v1/invoices` and `/v1/payments` as its callers
- **THEN** a folded "Called by" part holds the latency graphs of those two APIs

### Requirement: Every graph links to Datadog
Every graph SHALL have "Open in Datadog", opening its dashboard on the site of its region at the anomaly's time (or
the time shown), so the whole dashboard is always one click away in Datadog itself.

#### Scenario: Open in Datadog
- **WHEN** "Open in Datadog" is clicked on a graph whose anomaly lasted from 10:02 to 10:09 in EU
- **THEN** the dashboard opens on Datadog's EU site showing 10:02 to 10:09

## MODIFIED Requirements

### Requirement: A time range can be chosen
The Anomalies page and each full-screen graph SHALL offer Past 15 Minutes, 30 Minutes, 1 Hour, 3 Hours, 6 Hours,
12 Hours and 1 Day, all moving live with a pause and resume control. A range longer than the data kept SHALL show what
is kept and say so.

#### Scenario: Long range
- **WHEN** Past 1 Day is chosen while 6 hours are kept
- **THEN** the graphs show 6 hours and say "6 hours kept"

### Requirement: Only what is on screen costs memory
A chart SHALL be created only when its graph comes on screen and released when it is folded or far off screen; live
values SHALL be sent only for the graphs a page shows, the newest value winning for a slow page. With 200 anomaly
graphs and their context unfolded and scrolled through, the page SHALL stay under 200 MB of browser memory.

#### Scenario: Collapsed dashboards
- **WHEN** 19 of 20 dashboards are folded in the anomaly tree
- **THEN** no chart exists for their graphs and none of their values are sent to the page

#### Scenario: Large region
- **WHEN** a test region with 200 anomalies on graphs of 10 lines is unfolded and scrolled through
- **THEN** browser memory stays under 200 MB and the measured figure is written down

### Requirement: The pages fail on their own
When the Store cannot be reached, charts SHALL say "history unavailable" while live values, anomalies and reasons keep
working.

#### Scenario: Store stopped
- **WHEN** the Store is stopped and an anomaly is opened
- **THEN** its graph says "history unavailable" and the attention counter still updates

### Requirement: Navigation like Datadog
A top bar SHALL always show the page name, two tabs (Anomalies and Settings), the region switch, the time range,
the graph zoom, the connection and the notification and sound buttons. There SHALL be no left navigation. A filter
box SHALL narrow the anomalies by region, dashboard, panel or line, and the browser's Back SHALL undo the last step.

#### Scenario: Dashboard list
- **WHEN** US is chosen and "shop" is typed in the filter
- **THEN** each section keeps only US anomalies whose dashboard, panel or line contains "shop", and the counts follow

### Requirement: Widgets are drawn by their type
Timeseries SHALL be drawn as lines, bars or areas as configured, with their palette, legend and y-axis settings. A
query value or top list with an anomaly SHALL be drawn as a timeseries of its lines, titled with its widget's name.

#### Scenario: Query value
- **WHEN** a query value widget "p95 latency" has an anomaly
- **THEN** its graph is a timeseries of its line, titled "p95 latency"

#### Scenario: Bars
- **WHEN** a timeseries is configured to display bars
- **THEN** it is drawn as bars

### Requirement: Hovering shows the same moment everywhere
Hovering a timeseries SHALL show a vertical line at that moment on every graph shown for the same anomaly, including
its unfolded context graphs, each with a tooltip of its values then.

#### Scenario: Shared crosshair
- **WHEN** the pointer is at 10:04 on an upstream's latency graph with "Called by" unfolded
- **THEN** each caller's graph shows a line at 10:04

### Requirement: A metric opens in a side panel
Clicking a line or an anomaly band SHALL open a side panel beside the graph with the line's details (metric and
question, tags, dashboard › panel › graph, region, unit, kind, signal, normal at its last anomaly, newest value) and
its anomalies of the kept time, newest first, each with when (every display zone), how long, peak against normal (a
drop as a percentage), direction, state and reason.

#### Scenario: Open from a band
- **WHEN** the red band on a latency graph is clicked
- **THEN** the side panel shows that line's details and the anomaly at the top of its list

### Requirement: Anomalies are grouped by region, dashboard and service
The Anomalies page SHALL list the anomalies of the kept time in the sections Open, Needs a reason and Closed today
(past ones with the closed), each with its count. Inside a section, cards SHALL sit under a Region › Dashboard
heading, ranked worst first by size × minutes lasted; closed rows SHALL be newest first. With grouping by service, a
card SHALL name its service instead of its panel and graph.

#### Scenario: Two services
- **GIVEN** in US, dashboard "Shop", an open drop of 41 points on Success Rates `api=/v1/invoices` and a closed spike on Request Counts
- **WHEN** the Anomalies page is opened
- **THEN** the drop is a card in Open under "US › Shop", and the spike a row in Closed today

#### Scenario: Newest first
- **WHEN** anomalies closed at 09:03 and 13:04
- **THEN** the 13:04 one is listed first in Closed today

### Requirement: Each anomaly shows its lines and leads to them
Each anomaly SHALL show its state, start (every display zone), duration, and its lines with signal, direction and
peak against normal; clicking a line SHALL unfold its card with that line bold and open its side panel. The page SHALL
filter by state and by text, and SHALL update live.

#### Scenario: Open a line
- **WHEN** the line `status_code=500` of an anomaly is clicked
- **THEN** its card unfolds with that line bold in the graph, and its side panel opens

#### Scenario: Filter
- **WHEN** only "Needs a reason" is chosen
- **THEN** only anomalies waiting for a reason are listed, and empty levels are hidden

### Requirement: Graphs follow the zoom
Zooming the browser in SHALL make every graph on the Anomalies page bigger, and zooming out smaller; charts SHALL
redraw at their new size at once and the graph in view SHALL stay in view. A − / % / + control in the top bar SHALL
do the same for the graphs only, from 75% to 200%, remembered in the browser.

#### Scenario: Zoom in
- **WHEN** "Other graphs of Success Rates" shows 4 small graphs per row at 100% and the browser is zoomed to 200%
- **THEN** it shows 2 per row, each twice as wide and tall, with readable axes

#### Scenario: Graph zoom control
- **WHEN** the graph zoom is set to 150%
- **THEN** graphs are 1.5 times their size while the top bar and left navigation keep theirs, and the setting is kept after a reload

### Requirement: Catch-up is shown
While the Collector catches up after a start, the top of the Anomalies page SHALL show "Catching up" with the time
span and percent done.

#### Scenario: After a night off
- **WHEN** the system starts after 9 hours off
- **THEN** the page shows "Catching up 9 h · 0%" rising to 100%, then nothing

### Requirement: Anomalies show on the graph itself
Opening an anomaly or a line SHALL highlight it on its graph: its lines bold, the others faded, its bands labelled
with what happened, and a dot at each peak. The side panel SHALL hold details, anomalies and the reason box without a
graph of its own, and the page SHALL make room for it so the graph stays visible. Closing the panel SHALL end the
highlight.

#### Scenario: Open a line
- **WHEN** `api=/v1/devices` latency is opened while its anomaly of 3.2 × lasted from 10:00 to 10:10
- **THEN** its graph draws that line bold and the others faded, labels the band "latency up · 3.2×", marks its peak with a dot and its value, and the panel beside it shows the details and reason box with no graph

#### Scenario: Close the panel
- **WHEN** the panel is closed
- **THEN** the graph draws all its lines normally again

### Requirement: Anomalies are listed by panel
With grouping by graph, each card SHALL be titled Panel › Graph: the widget's name and, for a small graph of a split
group, its tag value ("all" for a whole widget).

#### Scenario: Tree
- **WHEN** a dashboard has open anomalies on "Request Counts" and on two small graphs of "Success Rates"
- **THEN** under its Region › Dashboard heading, Open shows three cards: "Request Counts › all", "Success Rates › api=/v1/devices" and "Success Rates › api=/v1/invoices"

### Requirement: Waiting anomalies are always one click away
The Anomalies tab SHALL show how many anomalies wait for a reason; clicking it SHALL show the page at Needs a reason.
It SHALL update live.

#### Scenario: Needs a reason
- **WHEN** two anomalies wait for a reason
- **THEN** the Anomalies tab shows a badge "2" and clicking it scrolls to Needs a reason with both

### Requirement: A reason can be given in the side panel
An anomaly that is open or calm, not BAU and without a reason SHALL have a reason box (1 to 500 characters) on its
card under Needs a reason and in its line's side panel, with "same reason for all n lines of this anomaly". Sending
SHALL show "saving…" until the Detector's answer arrives, and "not saved" with the reason when it fails. BAU anomalies
SHALL be dimmed with "BAU" and no reason box.

#### Scenario: One line
- **WHEN** "bad deploy" is sent on the card of a waiting anomaly of `status=500`
- **THEN** "saving…" shows, then the reason with its time, and the card moves to Closed today

### Requirement: Light or dark like the computer
The page SHALL follow the computer's light or dark setting, with the colours of the Incidents page of 16-18 Nov 2026.

#### Scenario: Dark mode
- **WHEN** the Mac is in dark mode
- **THEN** the page is dark, its graphs readable

## REMOVED Requirements

### Requirement: Opening a dashboard is cheap
**Reason**: Whole dashboards are no longer drawn; Datadog shows them (user, 23 Nov 2026: "Why re-invent the wheel?").
**Migration**: Use "Open in Datadog" on any graph. One anomaly's graph is one Store call, as before.

### Requirement: Dashboards are laid out as in their JSON
**Reason**: There is no dashboard page any more, so no layout to copy.
**Migration**: The JSON still decides what is collected and how graphs are named. The layout is seen in Datadog.

### Requirement: The top bar shows the dashboard's variables and time
**Reason**: Without a dashboard page there is no dashboard top bar.
**Migration**: The variables still filter what is collected; their values are in the Settings page and in Datadog.

### Requirement: Tables, rings, splits and notes are drawn too
**Reason**: Only graphs with anomalies are drawn. Tables, rings and notes have none of their own.
**Migration**: A split group's small graph is still drawn when it has an anomaly. The rest is seen in Datadog.

### Requirement: Comfortable or compact
**Reason**: It packed a whole dashboard tighter; with a few graphs the zoom does the same.
**Migration**: Use the browser zoom or the − / % / + control.

### Requirement: Worst anomalies on each dashboard
**Reason**: The Anomalies page itself is now ranked worst first, so a strip per dashboard repeats it.
**Migration**: The ranking (state, then size × minutes lasted) orders the graphs in the anomaly tree.
