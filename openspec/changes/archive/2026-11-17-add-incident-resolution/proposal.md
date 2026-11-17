# Proposal: add-incident-resolution

## Why

The user asked (17 Nov 2026): **an anomaly is not closed until someone gives its reason, and the reason is kept.**
Today an incident closes by itself once all its lines have been calm for `calm-period` (2 minutes), so a problem can
come and go without anyone writing down what it was, and the history added in `add-incident-history` shows
"reason: —" everywhere. The live page also shows incidents left open for days (their lines stopped sending data),
with no way to close them.

Decided with the user: an incident whose metrics are back to normal but has no reason **stays open until a reason is
given**, and never closes by itself; if it spikes again meanwhile, it continues as the same incident.

## What Changes

| Step | In plain words |
|---|---|
| **A new state: calm** | When every line of an incident has been normal for `calm-period`, the incident becomes **calm** (needs a reason) instead of closed. Its end time is recorded (how long the problem lasted); it stays on the page in a **"Needs a reason"** list, whatever the day |
| **Calm, then it spikes again** | A calm incident whose group spikes again goes back to **open**, the same incident (one problem, one reason) |
| **Closing needs a reason** | Each open or calm incident has a reason box on the page. Giving a reason (1–500 characters) **closes** it at once, open or calm: an open one is closed by a person who knows why (for example a stale incident whose lines stopped sending). A later spike of that group is a new incident |
| **The reason is kept** | It is saved with the incident in the Detector's database (in the same transaction as its event, as every incident change is), carried in the closing event, and shown in the line history (`add-incident-history` fills it in by itself) and later in reports |
| **One writer** | The page sends "close this incident with this reason" to the Notifier, which puts it on a new Kafka topic, `incident-commands`; the Detector, the only one that changes incidents, applies it and announces the result as usual. The page shows "saving…" until the closing event comes back (or "not saved" after 10 s) |
| **Never lost while waiting** | Open and calm incidents are never removed by the Detector's clean-up, and the Detector re-announces them twice a day, so a Notifier rebuilding from Kafka's 2 days still sees an incident that has waited longer for its reason |
| **Old incidents** | Incidents closed before this change stay closed; their reason shows "—" (they closed before reasons existed) |

## How we will know it works

| Check | Expected |
|---|---|
| Calm, not closed | An incident whose lines are normal for 2 minutes becomes calm, appears under "Needs a reason", is not closed |
| Spikes again | The same incident goes back to open; no second incident |
| Reason closes | A reason given on the page closes the incident within a second; the reason is in the Detector's database, in the closing event, and in the line's history |
| Open closed by a reason | A stale open incident closed with "lines stopped: test environment switched off" is closed; a later spike opens a new incident |
| Empty or too long | Refused on the page and by the Detector |
| Restarts | Restarting the Notifier or the Detector keeps calm incidents and given reasons |
| Waits longer than 2 days | A calm incident 3 days old is still on the page after a Notifier restart (re-announced) |
| Old incidents | Closed before the change: still closed, reason "—" |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `message-contracts`: the calm state, an incident's reason and when it was resolved, and the close command.
- `anomaly-detection`: calm instead of closing by itself; closing only by a reason; kept until then; re-announced.
- `live-alerting`: "Needs a reason", the reason box, saving feedback, reasons in history.

## Out of scope

| Not in this change | Why |
|---|---|
| Who gave the reason (users, sign-in) | The page has no users; the time is recorded. Later, if the page is shared |
| Editing a reason after closing | Not asked; a closed incident's reason is final |
| Reasons in reports | `add-reports-on-demand` (next) shows them |
| Suggested reasons (from earlier ones) | The line history already shows past reasons next to the box |
| Keeping reasons longer than incidents | Decided with the user (17 Nov): reasons live as long as incidents (2 days, up to 7 by setting); reports keep them in their files |

## Impact

- `app/contracts`: `IncidentState.INCIDENT_STATE_CALM`, `Incident.reason`, `Incident.resolved_ms`, `ResolveIncident`
  command; topic `incident-commands` (platform script).
- `app/detector`: calm state, reopen from calm, the command reader, clean-up rule, re-announcing, tests.
- `app/notifier`: `POST /incidents/{id}/reason` → command; board keeps calm incidents; the page (list, box,
  feedback); history reads the reason.
- `platform/create-topics.sh`: the new topic. No database schema change (the reason is in the stored incident).
