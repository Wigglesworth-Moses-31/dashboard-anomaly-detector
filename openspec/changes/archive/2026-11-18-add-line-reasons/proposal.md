# Proposal: add-line-reasons

## Why

On the user's other machine (18 Nov 2026) one incident grouped seven lines of one API: 404 errors up (6 vs 2),
three partners gone silent, two partners busier than usual. They are different things, but `add-incident-resolution`
asks **one reason for the whole incident**, which the user found hard to give.

Decided with the user (18 Nov):

| Question | Decision |
|---|---|
| Reasons when an incident holds several anomalies | **A reason per line** (per anomaly); a "same reason for all" shortcut when one cause explains everything |
| Lines that are usually nothing | **"general" up/down and errors down over a period are business as usual: mark them BAU and close them without asking** |

"general" is the signal of lines no rule recognises (for example requests per partner). Their **silence is BAU
too** (user, 18 Nov): a partner with no requests for a while is usual.

## What Changes

| Step | In plain words |
|---|---|
| **A reason per line** | Each line of an incident has its own reason box. A line's reason is kept with the line, shown next to it and in its history. A reason can be given while the line is still spiking or once it is calm |
| **Same reason for all** | One button gives the typed reason to every line of the incident that still needs one |
| **BAU lines** | A line whose anomaly is in the BAU list (`detector.yml`, default: signal `general` in any direction, and `errors` down) is marked **BAU** when it joins an incident: it needs no reason, is shown dimmed with "BAU", and its reason is recorded as "BAU (business as usual)" |
| **When an incident closes** | When it is calm **and** every line that is not BAU has a reason. The incident's own reason becomes a short summary ("3 reasons" or the one reason when all are the same) |
| **Only BAU** | An incident made only of BAU lines does not notify, is shown dimmed, and closes by itself when calm, with reason "BAU" |
| **Calm then again** | Unchanged: a spike while calm opens the same incident again. A line that already had a reason keeps it unless it spikes again, which clears its reason (it needs a new one) |
| **History** | Each line's history shows that line's own reason (or "BAU") |
| **Settings, not code** | The BAU list is in `detector.yml` (`bau:`), changed by commit like every rule |

## How we will know it works

| Check | Expected |
|---|---|
| Mixed incident | 404 errors up + 3 partners silent + 2 partners "general up": only the 404 line asks for a reason; the five others show "BAU" |
| Close by lines | After the 404 line gets "release 2.3 retried calls to xpc" and the incident is calm, it closes; the line keeps its reason; the incident's reason is that one reason |
| Same for all | Two lines needing reasons, "same reason for all" with one text: both get it; the incident closes when calm |
| Only BAU | An incident of only "general up" lines: no notification, dimmed, closes by itself when calm with "BAU" |
| Errors down | A 4xx count dropping (if a rule judges it down): BAU |
| Line spikes again | A reasoned line that spikes again while the incident is open needs a new reason |
| Setting | Removing `general` from the BAU list makes those lines need reasons again, without a restart |
| History | A line's history shows its own reason or "BAU" |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `message-contracts`: a line's own reason, when it was given, and whether it is BAU; the close command can name one
  line (or all).
- `anomaly-detection`: reasons per line; BAU lines from settings; the incident closes when calm and every non-BAU
  line has a reason; BAU-only incidents close by themselves when calm.
- `live-alerting`: a reason box per line, "same reason for all", BAU shown dimmed, no notification for BAU-only
  incidents, history shows line reasons.

## Out of scope

| Not in this change | Where |
|---|---|
| Visual style, clearer words for signals ("no traffic from cox") | `improve-incident-page`, next |
| Showing what lines share only once (long repeated paths) | `improve-incident-page` |
| Settings page (dashboard CRUD) | After these two; proposal and specs already written |

## Impact

- `app/contracts`: `AffectedSeries.reason = 13`, `AffectedSeries.resolved_ms = 14`, `AffectedSeries.bau = 15`;
  `ResolveIncident.series_id = 5` (empty = every line still needing a reason).
- `app/detector`: per-line reasons, the BAU list from settings, closing rule, tests.
- `app/notifier`: the endpoint takes a line (or all); history reads line reasons; page: reason per line, "same for
  all", BAU dimmed, no notification for BAU-only incidents.
- `config-template/detector.yml`: `bau:` with the defaults; README upgrade note.
