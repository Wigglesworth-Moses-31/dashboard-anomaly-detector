# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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
