# Proposal: add-collector

## Why

This is the first service with real work, and it removes the manual part of the problem: nobody has to set
up a monitor per panel. The Collector reads the chosen dashboards, finds every panel by itself, and keeps
their newest numbers flowing into Kafka in small steps that keep short spikes visible. It must be built while
the personal Datadog trial is active (it ends around 26 November 2026), and it records real responses so later
services can be tested without Datadog.

## What Changes

The Collector goes from an empty skeleton to a working service:

| Step | In plain words |
|---|---|
| **Read dashboards** | Fetch each watched dashboard's definition and find every panel's question (also inside groups, split groups, tables and charts) |
| **Fill in the dashboard filters** | Dashboards use placeholders such as `$env`; the Collector fills them from settings (or the dashboard's defaults) |
| **Ask Datadog for the numbers** | For each panel, ask for the last 1-2 minutes in 10-second steps, keeping the **highest** value per step (counts are added up per step); Datadog also computes panel formulas such as success rate % |
| **Turn answers into messages** | One `SeriesDescriptor` per new metric line, `MetricPoints` on every poll; gaps left out, the unfinished step marked partial; a finished step is not sent twice |
| **Know what each number is** | Kind and unit from settings rules first, then Datadog's own metadata, else "unknown" |
| **Stay polite to Datadog** | Follow its rate-limit headers, retry briefly on errors, and never let one failing panel stop the others |
| **Record and replay** | Optionally save every raw Datadog answer to files; a "replay" source can run the Collector from those files with no Datadog at all |

## Capabilities

### New Capabilities
- `metric-collection`: finding every panel on the watched dashboards and keeping their newest numbers flowing,
  without hiding short spikes, without overloading the source, and with recordings for testing.

### Modified Capabilities
None. The two scenarios left open by `message-contracts` (gaps are left out, the unfinished step is marked
partial) are now produced by the Collector and verified here, without changing that spec's requirements.

## Out of scope

| Not in this change | Where |
|---|---|
| Saving the numbers | `add-store` |
| Spotting spikes | `add-detector` |
| Datadog monitors, APM traces, logs | Not part of this system |
| Sources other than Datadog (Grafana, Prometheus) | Later; the Collector already reads through one interface |
| Other Datadog accounts | Built and tested on a personal trial account only |

## Impact

- `app/collector`: most of the new code (dashboard reader, query planner, scheduler, rate limiter, fetcher,
  classifier, publisher, recorder, replay source) and its tests.
- `config-repo/collector.yml`: new settings (dashboard filter values, classification rules, concurrency,
  recording). No code change is needed to change any of them.
- Datadog (personal trial): read-only calls only: dashboard definitions, metric metadata, timeseries queries.
  Measured limit for the timeseries endpoint: 300 requests per 10 seconds.
- Kafka: the Collector starts writing to `metric-series` and `metric-points`.
