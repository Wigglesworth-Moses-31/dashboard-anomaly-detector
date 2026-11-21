## ADDED Requirements

### Requirement: The gap since the last start is caught up
At every start the Collector SHALL ask the Store for each collected view's newest saved value and, when it is older
than the normal poll window, fill the gap up to now, at most `backfill-new-views` (24 hours by default), with the
backfill job: windows oldest first, the view's live polling held until done, values judged by the Detector as the
past. Nothing is needed before a shutdown, so a crash or power cut is caught up too.

#### Scenario: Off for 9 hours
- **WHEN** the system was stopped at 23:00 and starts at 08:00
- **THEN** the Collector fills 23:00 to 08:00 for every view in 3 windows of 4 hours, then polls live, and the graphs have no hole

#### Scenario: Off for 3 days
- **WHEN** the system starts after 3 days off
- **THEN** only the last 24 hours are filled

#### Scenario: Short restart
- **WHEN** the Collector restarts within a minute
- **THEN** nothing is caught up beyond the normal poll window

#### Scenario: Store not reachable
- **WHEN** the Store cannot be asked for 2 minutes
- **THEN** live polling starts without a catch-up, and the log says so
