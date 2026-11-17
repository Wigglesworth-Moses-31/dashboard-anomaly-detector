# Spec Delta

## Purpose

Shows every watched dashboard's panels and metric lines live on the system's own page, whether or not there is an
incident, so the user can see the metrics without opening Datadog.

## ADDED Requirements

### Requirement: The page has a Dashboards tab
The live page SHALL offer two tabs: Incidents (the existing page, unchanged) and Dashboards. The Dashboards tab
SHALL list every watched dashboard by title, and choosing one SHALL show its panels. The page SHALL remember the
chosen tab, dashboard and range when reloaded.

#### Scenario: Choosing a dashboard
- **GIVEN** the system watches the dashboards "Lab: API platform" and "Lab: Shop"
- **WHEN** the user opens the Dashboards tab and chooses "Lab: Shop"
- **THEN** the page shows the panels of "Lab: Shop" only

#### Scenario: Incidents tab unchanged
- **WHEN** the user switches back to the Incidents tab
- **THEN** it shows today's incidents exactly as before, and new incidents still notify

#### Scenario: Dashboard added to the settings
- **WHEN** a dashboard id is added to the Collector's settings and the Collector has read it
- **THEN** it appears in the Dashboards list without a restart or a page reload

### Requirement: Panels follow Datadog's groups and order
Panels SHALL appear under their Datadog group headings, groups and panels in the dashboard's own order. Panels
outside any group SHALL appear in their own place in that order. Exact sizes and positions are not copied; the
grid SHALL fit a narrow screen without sideways scrolling.

#### Scenario: Grouped dashboard
- **GIVEN** a dashboard with a group "Upstream" holding "Success rate" then "Latency", and a group "App" after it
- **WHEN** the dashboard is shown
- **THEN** the "Upstream" heading comes first with "Success rate" before "Latency", then the "App" heading

#### Scenario: Narrow screen
- **WHEN** the page is 375 pixels wide
- **THEN** panels stack in one column and nothing scrolls sideways

### Requirement: Each panel shows all its lines
Each panel SHALL be a line chart of every metric line the system collects for it, with the panel title, the unit,
a legend naming each line by what makes it different (for example `status_code:500`), and each line's newest
value. Panels with no metric query SHALL not be shown.

#### Scenario: Panel with one line per status code
- **GIVEN** a panel "Count" with lines for `status_code` 200, 404 and 500
- **WHEN** the dashboard is shown
- **THEN** the panel draws three lines, and the legend lists `status_code:200`, `status_code:404`,
  `status_code:500` with their newest values

#### Scenario: Line with no data yet
- **WHEN** a line has no values in the chosen range
- **THEN** its legend entry says "no data" and the other lines are drawn normally

### Requirement: A time range can be chosen
The page SHALL offer the time ranges from the settings (default 15m, 1h, 3h, 1d, 2d; default range 1h). Ranges of
3 hours or less SHALL use the 10-second values; longer ranges SHALL use per-minute values and say "per minute".
Times SHALL be shown in the shared time zone.

#### Scenario: Short range
- **WHEN** the user picks 1h
- **THEN** each line shows up to 360 ten-second values

#### Scenario: Long range
- **WHEN** the user picks 2d
- **THEN** each line shows per-minute values, and the page says "per minute"

#### Scenario: Shared time zone
- **GIVEN** the shared time zone is Asia/Kolkata
- **WHEN** a panel's time axis is shown
- **THEN** its times are Asia/Kolkata times

### Requirement: Panels update live
While a dashboard is shown, each new value of its lines SHALL be added to the panels within 5 seconds of reaching
the system, without the page asking again. A viewer that falls behind SHALL get the newest values, never a
backlog, and SHALL never slow other viewers or the incident stream.

#### Scenario: New step arrives
- **GIVEN** the 15m range is shown
- **WHEN** the Collector sends a new 10-second value for a shown line
- **THEN** that panel shows it within 5 seconds, and the oldest step leaves the left edge

#### Scenario: Stalled tab
- **GIVEN** a tab that reads nothing for a minute
- **WHEN** it reads again
- **THEN** it gets the newest value of each line, other viewers were not delayed, and incidents kept arriving

### Requirement: Short spikes stay visible
When a line has more values than the chart has room for, the values SHALL be thinned so that each pixel column
keeps both its lowest and its highest value. A single 10-second spike SHALL remain visible at every range.

#### Scenario: One spike in two days
- **GIVEN** a line that is calm for 2 days except one 10-second step at 10 times its normal value
- **WHEN** the 2d range is shown
- **THEN** the chart shows the spike at its full height

### Requirement: Incidents are shaded on the panels
Where an incident known to the page covered a line, the panel SHALL shade that time, and pointing at the shading
SHALL name the incident. An open incident's shading SHALL grow until it closes.

#### Scenario: Incident on a shown line
- **GIVEN** an incident "/v1/invoices: latency up" covered the Latencies line from 10:02 to 10:06
- **WHEN** the Latencies panel is shown at the 1h range
- **THEN** 10:02 to 10:06 is shaded on it, and pointing at the shading shows "/v1/invoices: latency up"

### Requirement: The page says how its panels differ from Datadog
The Dashboards tab SHALL say in one line that each 10-second step holds the step's highest value (or total, for
counts), so peaks can be higher than on Datadog's own graph.

#### Scenario: Comparing with Datadog
- **WHEN** the Dashboards tab is shown
- **THEN** that line is visible under the time range picker

### Requirement: The Dashboards tab fails on its own
If past values cannot be read, the Dashboards tab SHALL say "history unavailable" within 2 seconds and keep
showing live values as they arrive; the Incidents tab SHALL keep working.

#### Scenario: Store stopped
- **GIVEN** the Store is stopped
- **WHEN** the user chooses a dashboard
- **THEN** the tab says "history unavailable" within 2 seconds, and the Incidents tab still shows new incidents

### Requirement: Opening a dashboard is cheap
Opening a dashboard SHALL read the past values of all its lines in one request to the Store, not one per line,
and SHALL take under 1 second for 200 lines over 3 hours.

#### Scenario: Large dashboard
- **GIVEN** a dashboard with 40 panels and 200 lines
- **WHEN** the user opens it at the 3h range
- **THEN** the Store is asked once, and the panels are drawn within 1 second

### Requirement: Dashboard view settings live outside the code
The ranges offered, the default range, the most points per line and the Store address SHALL come from the
Notifier's settings and change without a restart. A setting that breaks a rule (for example a range longer than
the 2 days kept) SHALL be refused, keeping the old value.

#### Scenario: Range beyond what is kept
- **WHEN** someone sets the ranges to include `7d`
- **THEN** the change is refused, the old ranges stay, and the Notifier's settings page says why

### Requirement: Health shows how the Dashboards tab is doing
The Notifier's health SHALL show dashboards opened, Store requests and the slowest one, live values pushed, and
viewers on the Dashboards tab.

#### Scenario: After opening a dashboard
- **WHEN** a viewer opens a dashboard
- **THEN** the health shows one more dashboard opened, one more Store request, and one viewer on the tab
