# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Incidents waiting for a reason are listed
The page SHALL list calm incidents under "Needs a reason", with when they started and became calm, how long they
lasted, and their lines; the list SHALL show how many are waiting.

#### Scenario: Two waiting
- **WHEN** two incidents are calm without a reason
- **THEN** "Needs a reason (2)" lists both

### Requirement: A reason can be given on the page
Every open and calm incident SHALL have a reason box (1 to 500 characters). Sending it SHALL show "saving…" until the
incident's closing event arrives, then the incident shows as closed with its reason; when no closing event arrives
within 10 seconds, the page SHALL say "not saved" and keep the text. Empty and too long reasons SHALL be refused on the
page.

#### Scenario: Reason given
- **WHEN** a reason is typed for a calm incident and sent
- **THEN** within a second the incident is closed on the page and shows the reason with the time, in the display time zones

#### Scenario: Nothing came back
- **WHEN** the Detector does not answer within 10 seconds
- **THEN** the page says "not saved" and the typed reason stays in the box

### Requirement: Reasons show in a line's past
A line's history SHALL show each earlier incident's reason, or "—" when none was given.

#### Scenario: Earlier reason
- **WHEN** a line's earlier incident was closed with "deploy rolled back"
- **THEN** that entry of the line's history shows "reason: deploy rolled back"
