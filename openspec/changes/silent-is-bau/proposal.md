# Proposal: silent-is-bau

## Why

On 18 Nov 2026 the user said "Yes, silent is BAU": a line that goes silent for a while (no requests, no values) is
business as usual, whatever its signal. Today only silence of unrecognised (`general`) lines is BAU, so silent
latency or traffic lines of a service still ask for a reason, and incidents whose data stopped stay open for days
(the page showed several open for 65 h). Split out of `add-line-reasons` (archived) when that change was paused, so
the archived specs say only what was built.

## What Changes

| What | Change |
|---|---|
| **Every silent line is BAU** | The default BAU list gains "any signal, going silent" (`{ signal: "*", direction: silent }`, `*` = any signal) |
| **Silent-only incidents close at once** | An incident made only of BAU lines that are all silent closes at once with "BAU": a silence that never ends never becomes calm |
| **Waiting incidents are marked again** | When the BAU list loads (at start or after a commit), the lines of incidents still open or calm are marked again, so incidents from before the change are covered too |

## How we will know it works

| Check | Expected |
|---|---|
| Silence | A latency line and a traffic line of one service go silent: both BAU, the incident closes at once with "BAU", no notification |
| Mixed | 404 errors up + 2 silent lines: only the 404 line asks for a reason |
| From before | After the upgrade, the open incidents of only silent lines on the page close with "BAU" within a minute of start |
| Setting | Removing the `*` silent entry and committing: new silent lines need reasons again, without restart |

## Capabilities

### Modified Capabilities
- `anomaly-detection`: silent lines are BAU by default; silent-only incidents close at once; waiting incidents are
  marked again when the BAU list loads.

## Impact

- `app/detector`: `BauSettings` accepts `*` as any signal; the book re-marks waiting incidents on configure and
  restore and closes BAU-only silent incidents; tests.
- `config-template/detector.yml`: the new entry; README upgrade note.
