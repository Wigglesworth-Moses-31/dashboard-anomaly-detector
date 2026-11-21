## ADDED Requirements

### Requirement: A line's memory stays within its window
The Detector SHALL keep, for each line, memory proportional to the values inside its history window, however long it
runs and however many values it has seen. Values that left the window SHALL NOT pile up in its working structures.

#### Scenario: A day of values on one line
- **WHEN** one line receives 24 hours of 10-second values with a 30-minute history window
- **THEN** the line holds at most twice the 180 values of its window, not the 8,640 values it has seen, and its median and wobble are the same as when computed from the 180 values directly

#### Scenario: A large backfill
- **WHEN** a backfill sends 24 hours of values for every line of every watched dashboard
- **THEN** the Detector reads all of it without running out of memory and catches up with the newest messages

### Requirement: The Detector reports its own size
The Detector SHALL log once a minute how many lines it watches and how much memory it uses, so that growth shows
before it becomes a failure.

#### Scenario: Size in the log
- **WHEN** the Detector has been running for a minute
- **THEN** its log has a line with the number of lines watched and the memory used and allowed
