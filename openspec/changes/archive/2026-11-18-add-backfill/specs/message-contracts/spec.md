# Spec Delta

## ADDED Requirements

### Requirement: Points and incidents say when they come from backfill
A batch of points SHALL be able to say it was pulled as backfill, and an incident SHALL be able to say it was found
in backfill. Both SHALL be new fields, so older readers keep working.

#### Scenario: Backfilled points
- **WHEN** the Collector sends a 2-day-old window of a new dashboard
- **THEN** its point batches say "backfill"

#### Scenario: Older reader
- **WHEN** a reader built before this change reads a backfilled batch
- **THEN** it reads the points as usual
