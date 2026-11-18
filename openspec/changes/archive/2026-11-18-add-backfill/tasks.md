# Tasks

## 1. Contracts and Collector

- [x] 1.1 `MetricPoints.backfill`, `Incident.found_in_backfill`; compatibility check, baseline, docs
- [x] 1.2 Collector: new views from the Store, hold, windows oldest first through the lane, send marked, release, progress, settings (design B2, B3); verify by tests

## 2. Detector and Notifier

- [x] 2.1 Detector: found in backfill, close by itself, continue live (design B4); verify by tests
- [x] 2.2 Notifier and page: no notification, "past · found in backfill", grey, progress in Settings (design B5)

## 3. Checks and docs

- [x] 3.1 Live: measure the window Datadog answers in full; README; `docs/verification/add-backfill.md`; all tests
