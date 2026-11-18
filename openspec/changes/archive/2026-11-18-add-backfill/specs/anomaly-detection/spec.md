# Spec Delta

## ADDED Requirements

### Requirement: Backfilled values are judged as the past
The Detector SHALL judge backfilled values in time order like live ones, so a line's normal is built from them and
live judging starts at once. An incident opened by backfilled values SHALL be marked found in backfill and, when its
lines are normal again, SHALL close by itself with the reason "found in backfill": no reason asked, no notification.
An incident found in backfill still going on when live values arrive SHALL continue as a live incident and be
announced as opened.

#### Scenario: A spike 30 hours ago
- **WHEN** a new dashboard's backfill holds a latency spike 30 hours ago that lasted 6 minutes
- **THEN** an incident with that start and end is stored, closed, marked found in backfill, with the reason "found in backfill"

#### Scenario: Still going on
- **WHEN** the backfill ends inside an anomaly and the next live values are still abnormal
- **THEN** the incident is announced as opened, no longer marked found in backfill

#### Scenario: Warm at once
- **WHEN** the backfill of a new line has been judged
- **THEN** its next live value is judged without waiting 10 minutes
