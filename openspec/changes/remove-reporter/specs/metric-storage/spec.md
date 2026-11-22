## MODIFIED Requirements

### Requirement: Raw points removed on request
The Store SHALL answer a request to remove raw points older than a given time, removing only raw points (never
per-minute summaries or line descriptions) and never points from the last 3 hours, which the per-minute summaries are
refreshed from. It SHALL refuse a time in the future and answer how many points it removed and up to which time. No
service calls it since the Reporter was removed; the Store's own limit removes old raw points.

#### Scenario: The Reporter cleans the day
- **WHEN** a client asks at 00:02 on the 17th to remove raw points older than 23:00 India time on the 16th
- **THEN** raw points before 21:02 are gone, those from 21:02 on remain, the per-minute summaries of the whole 16th
  remain, and the answer says 21:02 and how many points were removed

#### Scenario: The safety limit still applies
- **WHEN** no clean-up request comes at all
- **THEN** raw points older than `raw-safety-limit` (1 day) are still removed by the Store's own limit

#### Scenario: A time in the future
- **WHEN** a request asks to remove raw points older than tomorrow
- **THEN** it is refused with a reason and nothing is removed

### Requirement: Summary of one day
The Store SHALL answer, for one calendar day in a given time zone, the same summary as for the window from that
day's midnight to the next midnight in that time zone.

#### Scenario: Yesterday's summary in India time
- **WHEN** a client asks for the summary of 2026-11-15 in Asia/Kolkata
- **THEN** each line's summary covers 2026-11-15 00:00 to 24:00 India time

#### Scenario: A day equals its window
- **WHEN** the same day is asked for as a day and as the window from its midnight to the next
- **THEN** both answers are identical
