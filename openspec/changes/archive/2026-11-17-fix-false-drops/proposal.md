# Proposal: fix-false-drops

## Why

On the user's other machine (17 Nov 2026, a real account, many presets), the live page showed three kinds of false or
unreadable alarms:

| Seen | Cause |
|---|---|
| Eight saved views "traffic down" **at the same second** (13:04:10), each 5–25× below normal | **Late data.** A 10-second step is treated as finished as soon as its 10 seconds are over, sent once and judged. A busy Datadog account can still be receiving that step's counts 30–90 s later, so the newest step of every count looks like a sudden drop, everywhere at once. The lab account received data almost at once, so this never showed |
| "traffic down" on `status=404`, `499`, `400` | Fewer errors is good news, but the error rule matches only `status_code=4*`/`5*` tags; this dashboard's tag is `status`, so those lines fell to the "traffic, both ways" rule |
| "+5,000,000,000×" next to a value of 0 | The page divides normal by (almost) zero for a drop to 0 |

## What Changes

| Step | In plain words |
|---|---|
| **Settle delay** | A step is "finished" only when it ended at least `settle-delay` ago (default **60 s**, a Collector setting). Newer steps are sent as partial, as the unfinished step is today; the Detector already never judges partial steps. Cost: an anomaly is seen about 60 s later |
| **Late data is measured** | When a later poll brings a different value for a step already sent as finished, the Collector counts it, per source, with how late it was. Health shows `lateChanges` and `latestChangeAfterSeconds`, so the delay can be set from facts: if changes still arrive after 60 s, raise it |
| **Error counts alert up only** | The default rules treat `status=4*` and `status=5*` tags like `status_code=4*`/`5*`: errors up is an anomaly, errors down is not. Settings only (`detector.yml`), no code |
| **A drop to zero reads clearly** | The page shows "dropped to 0 (−100%)" instead of a ratio when the value is 0; other drops show "−92%" rather than "÷13×" |

## How we will know it works

| Check | Expected |
|---|---|
| Late data, replayed | A recording where a step's count first comes back low, then complete 40 s later: no incident with a 60 s settle delay; with settle delay 0 (today's behaviour) the false "traffic down" appears |
| Measured | The same recording shows `lateChanges: 1`, `latestChangeAfterSeconds: 40` |
| Status tags | A `status=404` count dropping to 0: no incident; rising 10×: "errors up" |
| Page | A line at 0 with normal 5 shows "dropped to 0 (−100%)" |
| Nothing else moves | Every existing test passes; line ids unchanged |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `metric-collection`: a step is finished only after the settle delay; late changes are measured and shown.
- `anomaly-detection`: status-count lines with a `status` tag count as errors (up only) in the default rules.
- `live-alerting`: drops shown as a percentage, a drop to 0 as "dropped to 0".

## Out of scope

| Not in this change | Where |
|---|---|
| Fetching the hours missed while the laptop was off | `add-catch-up` |
| Hierarchy, history, reasons, reports, settings page, system page | The following changes, in the agreed order |

## Impact

- `app/collector`: partial marking uses `settle-delay`; a late-change counter in the Emitter; health.
- `config-template/detector.yml`: `status=4*`, `status=5*` in the error rule.
- `app/notifier/src/main/resources/static/app.js`: the meter text.
- No contract change, no new topic, nothing in Datadog.
