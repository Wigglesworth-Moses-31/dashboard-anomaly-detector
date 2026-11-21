# Tasks: fix-backfill-faults

## 1. Store
- [x] 1.1 Cast the refresh times to timestamptz; give the range back on failure
- [x] 1.2 Live test: refresh a late range through `refreshLate()`; a failing refresh keeps the range

## 2. Detector
- [x] 2.1 `Window` rebuilds its heaps when stale entries exceed 2 × live + 16
- [x] 2.2 Test: 24 h of values (trend, noise, flat) keeps ≤ 2 × 180 + 16 entries; medians match brute force
- [x] 2.3 Log lines watched and heap use once a minute

## 3. Formula lines
- [x] 3.1 `DashService.method`: formula lines average unless a plain sum/difference of SUM queries
- [x] 3.2 Tests for the method rule
- [x] 3.3 Page: `lineName` (tags, alias, formula, metric) used everywhere a line is named; aliases from the dashboard JSON
- [ ] 3.4 Compare the user's Success Rate panel with Datadog (needs the panel JSON from the user's machine)

## 4. Scripts
- [x] 4.1 `-Xmx512m` for every service
- [x] 4.2 `scripts/check-config.sh`, called at start

## 5. Finish
- [x] 5.1 Build, unit tests, restart; check the page and logs
- [x] 5.2 README and `docs/verification/fix-backfill-faults.md`
