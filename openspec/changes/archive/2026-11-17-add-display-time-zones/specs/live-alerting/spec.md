# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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
