# Verification: anomaly-per-graph (22 Nov 2026)

| Check | Result |
|---|---|
| Contract | `View.split_tag` added; compatibility check passes against the updated baseline |
| Collector: dashboard reader | The lab dashboard's split groups name their tag (`api` for Success Rates, Latencies, Count; `upstream_service` for the upstream ones); other panels none |
| Collector: line description | A split-group line is described with `split_tag: api` and its widget id; other lines with none |
| Detector (`GraphScopeTest`) | Lines of three panels with only client, partner and status tags → **three** anomalies (two lines of one panel share one); two small graphs of a split group → two anomalies titled "Count · api=/v1/…"; the same dashboard in US and EU → two; `incident-scope: service` → the old single dashboard anomaly; `incident-scope: panel` is refused |
| Older scenarios | The 42 service-grouping scenarios run unchanged with `incident-scope: service` |
| Page | Anomalies page: "region › dashboard › panel (or service)"; Worst anomalies' first column "Graph" |
| All tests | contracts 28, service-kit 8, collector 128, store 18, detector 74, notifier 31, reporter 18, config-service 12 |

**Not seen live yet:** a new per-graph anomaly. The lab's sample traffic is off, so no new anomaly can open; the 19
anomalies already waiting keep their service grouping until they get a reason, as designed. The Collector sends the
split tag with a line's description the next time Datadog answers for it.
