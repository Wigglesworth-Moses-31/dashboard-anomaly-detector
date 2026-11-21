# incident-reporting Specification

## Purpose
Writes one readable Markdown report per time window (a day by default, never more than 24 hours) about the
incidents and the metric lines of that window, exactly once, and only then removes the window's raw points so the
database stays small.

## Requirements

### Requirement: One report per window
After each window ends, the Reporter SHALL write one Markdown file for it. By default a window is the calendar day
in the shared time zone (Asia/Kolkata). A report for a chosen day SHALL also be possible on request.

#### Scenario: End of the day
- **WHEN** 16 November ends in Asia/Kolkata
- **THEN** `reports/2026-11-16.md` is written within 5 minutes after midnight

#### Scenario: On request
- **WHEN** an operator asks for the report of 15 November
- **THEN** `reports/2026-11-15.md` is written from what the Store and Kafka still hold

### Requirement: The window is a setting, never more than a day
The window length SHALL be a setting that divides a day evenly (1, 2, 3, 4, 6, 8, 12 or 24 hours), starting at
midnight in the shared time zone. Longer windows, or lengths that do not divide a day, SHALL be rejected and the
previous setting kept.

#### Scenario: Six-hour windows
- **WHEN** the window is set to 6 hours
- **THEN** four reports are written per day, for 00-06, 06-12, 12-18 and 18-24, named like `2026-11-16_06-12.md`

#### Scenario: Too long
- **WHEN** the window is set to 48 hours
- **THEN** the setting is rejected and the previous one stays in use

### Requirement: The report says what happened
A report SHALL start with a headline: the number of incidents (and the previous window's number), the longest
incident, incidents still open at the window's end, and how complete the data was. It SHALL then list every incident
that started in the window, newest last, with its start, end and duration.

#### Scenario: Day with incidents
- **WHEN** 37 incidents started in the window and 2 were still open at its end
- **THEN** the headline names 37 incidents, the previous window's number, and 2 still open, followed by a table of all 37

#### Scenario: Quiet window
- **WHEN** no incident started in the window
- **THEN** a report is still written, saying there were no incidents

### Requirement: Each incident has its evidence
For each incident the report SHALL give a section with every line involved: its signal and direction, peak against
normal with the unit, the panel, and a link to each dashboard at the incident's time.

#### Scenario: Reading one incident
- **WHEN** "/v1/invoices: latency up, errors up" ran from 17:04:00 to 17:07:00
- **THEN** its section shows "latency up 1,569 ms, normal 138 ms (11.3x), Latencies" and a link to the lab
  dashboard from 16:59 to 17:12

### Requirement: The report shows patterns
A report SHALL list the groups with the most incidents in the window, the lines whose highest value was furthest
above their average (top 10), and the lines the Collector could not classify, so their kind can be set.

#### Scenario: Which API broke most
- **WHEN** billing had 5 incidents and /v1/firmware 1
- **THEN** "Broke most often" lists billing before /v1/firmware

#### Scenario: Unclassified lines
- **WHEN** 18 lines have an unknown kind
- **THEN** the report lists them by dashboard and panel

### Requirement: Exactly one report per window
Each window SHALL go through the steps pending, written, verified (the file read back and complete), cleaned. Running
again for the same window, including after a restart, SHALL finish the missing steps and SHALL NOT write a second
report.

#### Scenario: Restart during the report
- **WHEN** the Reporter restarts after writing the report but before the clean-up
- **THEN** after the restart the report is not written again, and the clean-up completes

### Requirement: Missed windows are caught up
After a stop, the Reporter SHALL report every window it missed, oldest first, as far back as the data still exists
(up to 24 hours by default), and SHALL say in the headline when a window's data was incomplete.

#### Scenario: Reporter down for a night
- **WHEN** the Reporter was stopped from 22:00 on the 16th to 09:00 on the 17th
- **THEN** after it starts, the report for the 16th is written

#### Scenario: Down for five days
- **WHEN** the Reporter was stopped for 5 days
- **THEN** after it starts, only the windows of the last 24 hours are reported, because older data is gone

### Requirement: Report files stay few
The output folder SHALL keep the most recent reports up to a set number (default 30) and remove older ones.

#### Scenario: The 31st report
- **WHEN** the 31st daily report is written
- **THEN** the oldest report file is removed

### Requirement: Health shows how the Reporter is doing
The Reporter's health SHALL show the last report written, the next window to report, windows waiting, the last
clean-up and how many points it removed, and the size of the report folder.

#### Scenario: A report is due
- **WHEN** a window has ended and its report is not yet written
- **THEN** the health page lists it as waiting

### Requirement: Report times in all display zones
Every time in a report SHALL appear in each display zone, in the same form as on the page.

#### Scenario: An incident in a report
- **WHEN** a report lists an incident that started at 13:04:10 UTC on 17 Nov 2026
- **THEN** its start reads `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`

### Requirement: Reports never remove data
Writing a report SHALL NOT remove raw points or summaries; data is removed only by age (10 days by default).

#### Scenario: After the report
- **WHEN** the report for 16 November has been written and verified
- **THEN** the raw points of 16 November are still stored
