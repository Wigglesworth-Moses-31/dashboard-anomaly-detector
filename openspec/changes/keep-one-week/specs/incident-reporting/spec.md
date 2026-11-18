# Spec Delta

## ADDED Requirements

### Requirement: Reports never remove data
Writing a report SHALL NOT remove raw points or summaries; data is removed only by age (7 days by default).

#### Scenario: After the report
- **WHEN** the report for 16 November has been written and verified
- **THEN** the raw points of 16 November are still stored

## MODIFIED Requirements

### Requirement: Missed windows are caught up
After a stop, the Reporter SHALL report every window it missed, oldest first, as far back as the data still exists
(up to 7 days), and SHALL say in the headline when a window's data was incomplete.

#### Scenario: Reporter down for a night
- **WHEN** the Reporter was stopped from 22:00 on the 16th to 09:00 on the 17th
- **THEN** after it starts, the report for the 16th is written

#### Scenario: Down for five days
- **WHEN** the Reporter was stopped for 5 days
- **THEN** after it starts, the five missed reports are written, oldest first

## REMOVED Requirements

### Requirement: Clean up only after the report is verified
**Reason**: Reports no longer remove data; everything is kept 7 days and removed by age (user, 18 Nov).
**Migration**: None; raw points stay until they are 7 days old.

### Requirement: Data still arriving is never removed
**Reason**: There is no clean-up after a report any more, so nothing still arriving can be removed by it.
**Migration**: None.
