# Spec Delta

## ADDED Requirements

### Requirement: Raw points removed on request
The Store SHALL answer a request to remove raw points older than a given time, removing only raw points (never
per-minute summaries or line descriptions) and never points from the last 3 hours, which the per-minute summaries are
refreshed from. It SHALL refuse a time in the future and answer how many points it removed and up to which time.

#### Scenario: The Reporter cleans the day
- **WHEN** at 00:02 on the 17th the Reporter asks to remove raw points older than 23:00 India time on the 16th
- **THEN** raw points before 21:02 are gone, those from 21:02 on remain, the per-minute summaries of the whole 16th
  remain, and the answer says 21:02 and how many points were removed

#### Scenario: The safety limit still applies
- **WHEN** no clean-up request comes for 3 days
- **THEN** raw points older than 2 days are still removed by the Store's own limit

#### Scenario: A time in the future
- **WHEN** a request asks to remove raw points older than tomorrow
- **THEN** it is refused with a reason and nothing is removed
