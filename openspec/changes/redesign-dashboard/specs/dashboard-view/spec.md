## MODIFIED Requirements

### Requirement: Anomalies are shown on the charts
Each anomaly of a line on a timeseries SHALL be shown as a light band over its own time span only, never over the
whole graph; hovering it SHALL name it; past ("found in backfill") and BAU anomalies SHALL be grey. A widget with an
anomaly SHALL show a small dot by its title (red open, amber needs a reason, grey past) instead of a red edge, and a
query value whose line has an open anomaly SHALL be shown in red.

#### Scenario: Incident on a shown line
- **WHEN** `/v1/invoices` latency had an anomaly from 10:02 to 10:09
- **THEN** the Latencies widget shows a light red band from 10:02 to 10:09 and nowhere else, a red dot by its title, and hovering the band names the anomaly

### Requirement: Navigation like Datadog
A top bar SHALL always show the region switch (one entry per watched region), a searchable dashboard picker whose
entries show their open-anomaly counts, the time range and Live. A breadcrumb SHALL show Region › Dashboard › Group ›
Widget, and the browser's Back SHALL return to the previous step. The left navigation SHALL offer only Dashboards,
Anomalies and Settings, with the attention counter.

#### Scenario: Dashboard list
- **WHEN** US is chosen and "shop" is typed in the dashboard picker
- **THEN** only US dashboards whose title contains "shop" are offered, each with its open-anomaly count, and choosing one opens it without leaving the top bar

### Requirement: Dashboards are laid out as in their JSON
A dashboard SHALL keep the order and grouping of its exported JSON, but every graph SHALL have a size in page units
(not a share of the window): a timeseries at least 360 px wide, a small graph of a split group at least 320 × 160 px.
As many as fit SHALL share a row; a widget narrower than that SHALL take its group's whole row. Group widgets SHALL be
boxes with their title and background colour, collapsible, holding their widgets.

#### Scenario: Ordered layout
- **WHEN** the lab dashboard, whose first group sits at x 0, y 0, width 12, height 4, is opened
- **THEN** that group spans the full width at the top, with its widgets in their JSON order inside it

#### Scenario: Automatic layout
- **WHEN** a dashboard without positions is opened
- **THEN** its widgets flow in their order, groups full width

## ADDED Requirements

### Requirement: Graphs follow the zoom
Zooming the browser in SHALL make every graph bigger with fewer per row, and zooming out SHALL make them smaller with
more per row; charts SHALL redraw at their new size at once and the widget in view SHALL stay in view. A − / % / +
control in the top bar SHALL do the same for the graphs only, from 75% to 200%, remembered in the browser.

#### Scenario: Zoom in
- **WHEN** a split group shows 4 small graphs per row at 100% and the browser is zoomed to 200%
- **THEN** it shows 2 per row, each twice as wide and tall, with readable axes

#### Scenario: Graph zoom control
- **WHEN** the graph zoom is set to 150%
- **THEN** graphs are 1.5 times their size while the top bar and left navigation keep theirs, and the setting is kept after a reload

### Requirement: Readable small graphs
A graph title SHALL fit on one line, shortened in the middle when too long, with the full title on hover. A value
axis SHALL show at most 4 ticks with short numbers (`99.8%`, `1.2k`, `350 ms`) that never overlap. Lines SHALL be
1.5 px in a colour-blind-safe palette with distinct colours for up to 10 lines, and text SHALL be readable in light
and dark.

#### Scenario: Long api title
- **WHEN** a small graph's title is `api: /accounts/_id_/gateways/_mac_/security/state`
- **THEN** it reads `api: /accounts/…/security/state` on one line and the full title on hover

### Requirement: Comfortable or compact
A switch in the top bar SHALL choose Comfortable (default) or Compact sizes, remembered in the browser.

#### Scenario: Compact
- **WHEN** Compact is chosen
- **THEN** graphs and gaps are smaller so more fit on screen, and Comfortable comes back after choosing it again

### Requirement: Worst anomalies on each dashboard
Each dashboard SHALL start with a "Worst anomalies" strip: its anomalies ranked by state (open, needs a reason,
closed, past), then by size × minutes lasted, size being how far the worst line is from normal. It SHALL show the top
5, unfold to all, and update live. Each row SHALL show rank, service, line, signal, size, how long and state; clicking
it SHALL scroll to its widget, highlight that line there and open its details panel. Group headers SHALL show their anomaly
count, also when folded.

#### Scenario: Ranking
- **WHEN** a dashboard has an open drop of 41 points lasting 18 minutes, an open spike of 3.2 × lasting 9 minutes and a waiting spike of 5 × lasting 4 minutes
- **THEN** the strip lists the drop first, the 3.2 × spike second and the waiting spike third

#### Scenario: Click a row
- **WHEN** the first row is clicked
- **THEN** the page scrolls to its widget, that widget highlights the line of the anomaly, and the details panel opens on it

#### Scenario: No anomalies
- **WHEN** a dashboard has no anomalies in the chosen range
- **THEN** the strip says so in one line

### Requirement: Show only one line
Clicking a line in a legend or in the side panel's table SHALL show only that line in that graph; clicking it again
SHALL show all lines. Ctrl- or Cmd-click SHALL add or remove one line. It SHALL work in a widget, in full screen and
in the side panel, and the axis SHALL fit the lines shown.

#### Scenario: One line
- **WHEN** a latency graph with 6 lines shows its legend and `/v1/devices` is clicked
- **THEN** only `/v1/devices` is drawn and the axis fits it; clicking it again draws all 6

#### Scenario: Two lines
- **WHEN** after that `/v1/invoices` is Cmd-clicked
- **THEN** `/v1/devices` and `/v1/invoices` are drawn

### Requirement: Catch-up is shown
While the Collector catches up after a start, the top bar SHALL show "Catching up" with the time span and percent done.

#### Scenario: After a night off
- **WHEN** the system starts after 9 hours off
- **THEN** the top bar shows "Catching up 9 h · 0%" rising to 100%, then nothing

### Requirement: Anomalies show on the graph itself
Opening a line SHALL highlight it on every graph of the dashboard that draws it: the line bold, the others faded, its
anomaly bands labelled with what happened, and a dot at each anomaly's peak. The details panel SHALL hold the line's
details, anomalies and reason box without a graph of its own, and the page SHALL make room for it so the graph stays
visible. Closing the panel SHALL end the highlight.

#### Scenario: Open a line
- **WHEN** `api=/v1/devices` latency is opened while its anomaly of 3.2 × lasted from 10:02 to 10:09
- **THEN** the Latencies graph draws that line bold and the others faded, labels the band "latency up · 3.2×", marks its peak with a dot and its value, and the panel beside it shows the details and reason box with no graph

#### Scenario: Close the panel
- **WHEN** the panel is closed
- **THEN** every graph draws all its lines normally again

### Requirement: Every graph can be enlarged
Every graph SHALL have a visible enlarge button, without needing to hover first, including each small graph of a split
group. Enlarging a small graph SHALL show only that graph's lines, titled with its group and tag value.

#### Scenario: One API's graph
- **WHEN** the enlarge button of the `api: /v1/devices` graph inside "Success Rates" is clicked
- **THEN** a full-screen view shows only `/v1/devices`'s success rate, titled "Success Rates · api: /v1/devices", with its lines table

### Requirement: A picked line always shows in its graph
A graph with many lines SHALL still offer a legend (scrolling when long), and a line that is picked or opened SHALL be
drawn in its graph even when it is not among the busiest lines the graph draws. While a line is opened, the value
axis SHALL fit that line so it is readable next to bigger lines.

#### Scenario: The 27th line
- **WHEN** a graph has 30 lines, draws its busiest 20, and the line at the bottom of its list is opened
- **THEN** that line is drawn bold in the graph, the others faded, the axis fits it, and its details open beside the graph
