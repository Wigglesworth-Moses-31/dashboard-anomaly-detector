# Tasks: calmer-anomalies

## 1. Detector: slow judging
- [x] 1.1 `judge-every` on rules and general; `slow-history`, `slow-min-history` with validation
- [x] 1.2 `LineWatch`: open period, averages handed to the state machine, slow limits, calm at once
- [x] 1.3 Rule matched before warm start; warm start sums into averages; history span per line
- [x] 1.4 A rule change between fast and slow refills the line from the Store
- [x] 1.5 Tests: 30-second dip, dip that lasts, errors stay fast, warm start, calm, rule change, validation
- [x] 1.6 Scenario fixtures keep `10s` so recorded scenarios keep their meaning

## 2. Settings
- [x] 2.1 `config-template/detector.yml`: `judge-every: 5m` on success-rate, latency, saturation, traffic, general; slow-history keys
- [x] 2.2 `scripts/check-config.sh`: compare the new keys
- [x] 2.3 Local `config-repo/detector.yml` takes the template (commit in config-repo, not in this repo)

## 3. Page in the old look
- [x] 3.1 Top bar: name, tabs, region, range, zoom, live pill, notifications, sound
- [x] 3.2 Catch-up strip, day cards, sections Open / Needs a reason / Closed today, Region › Dashboard headings
- [x] 3.3 Cards: Panel › Graph, edge colour, line table, judged tag, Open in Datadog, reason box
- [x] 3.4 Unfolded card: graph with all lines, band, bold line, peak dot, show one line, enlarge, crosshair, live values
- [x] 3.5 Folded context: other graphs of the panel, Calls / Called by
- [x] 3.6 Filter, chips, tab badge, side panel for a line
- [x] 3.7 Remove dashboard drawing code and `/dash/range`

## 4. Check
- [x] 4.1 All module tests pass
- [x] 4.2 Live page checked in light and dark, zoom, side panel, full screen, split graphs, Called by (real anomalies + test cards)
- [x] 4.5a With lab traffic: errors open 82-95 s after the burst (2 steps + Datadog and settle delay), as before
- [ ] 4.5b With lab traffic for 45+ minutes: 5-minute lines judged on live data
- [x] 4.3 Memory with many cards unfolded
- [x] 4.4 `docs/verification/calmer-anomalies.md`
