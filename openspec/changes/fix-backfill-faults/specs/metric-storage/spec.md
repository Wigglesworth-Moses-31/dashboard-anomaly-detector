## ADDED Requirements

### Requirement: Summaries of late values are refreshed until they succeed
When values arrive for times the regular summary refresh no longer covers (a backfill, late data), the Store SHALL
refresh the per-minute summaries for that time range. If that refresh fails, the Store SHALL keep the range, log the
failure, and try it again on its next housekeeping run, merged with any newer late range, until it succeeds.

#### Scenario: A backfill's summaries are refreshed
- **WHEN** the Collector backfills the last 24 hours of a new dashboard
- **THEN** the per-minute summaries for those 24 hours exist after the next housekeeping run, and the log says which range was refreshed

#### Scenario: A failed refresh is tried again
- **WHEN** the refresh of a late range fails because the database is briefly unreachable
- **THEN** the range is not forgotten: the next housekeeping run refreshes it, together with any late range that arrived meanwhile
