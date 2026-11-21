# Spec Delta

## MODIFIED Requirements

### Requirement: Missed windows are caught up
After a stop, the Reporter SHALL report every window it missed, oldest first, as far back as the data still exists
(up to 24 hours by default), and SHALL say in the headline when a window's data was incomplete.

#### Scenario: Reporter down for a night
- **WHEN** the Reporter was stopped from 22:00 on the 16th to 09:00 on the 17th
- **THEN** after it starts, the report for the 16th is written

#### Scenario: Down for five days
- **WHEN** the Reporter was stopped for 5 days
- **THEN** after it starts, only the windows of the last 24 hours are reported, because older data is gone
