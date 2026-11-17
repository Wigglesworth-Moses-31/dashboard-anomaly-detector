# Verification: add-incident-resolution

17 Nov 2026. An incident becomes calm (not closed) when its lines are normal again; only a person's reason closes
it; the reason is kept and announced.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **258 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | 28 passed | `INCIDENT_STATE_CALM`, `reason`, `resolved_ms`, `ResolveIncident` pass the compatibility check; baseline refreshed on purpose; command checks (reason 1–500 characters) |
| Detector scenarios | 58 passed | Calm after 2 min with its end time, nothing closes by itself (5 older scenarios now expect calm); calm then a spike an hour later → OPENED again with the same id; a reason closes a calm incident (reason, resolved time) and the next spike is a new incident; the same command twice closes once; an open incident closes at once; empty, 501 characters and unknown change nothing; waiting incidents re-announced after 12 h, not before; a calm incident restored after a restart can still open again |
| Notifier | 21 passed | `POST /incidents/{id}/reason` → 202 with the command id; empty, too long and missing → 400; Kafka not taking it → 503; the line history shows the reason |

## Live (the real system; the incidents on this machine are from 15 Nov)

| Check | Result |
|---|---|
| Migration and topic | `one_waiting_per_group` (OPEN or CALM) replaces the old index; topic `incident-commands` created |
| Calm, live | After the start the Detector made 2 incidents CALM; the page showed "Needs a reason (2)" with `calm since 08:21:10 UTC · 13:51:10 IST · 03:21:10 EST · lasted 3 min` |
| Reason for a calm incident (typed in the page) | "saving…"; the Notifier sent the command at 19:42:07.948, the Detector closed it at .951; the database row is CLOSED; the closing event in Kafka carries the reason; after a reload "Needs a reason (1)" |
| Reason for a stale open incident | `202` → CLOSED within 3 s; an empty reason → `400` |
| Restart of Detector and Notifier | The remaining calm incident is still CALM in the database and under "Needs a reason (1)"; the Notifier rebuilt 153 not-closed incidents (as before) |
| Typing "/" in a reason | Stays in the reason box (the filter shortcut no longer takes it) |

## Notes from the live check

| Seen | Why it is right |
|---|---|
| The page did not redraw by itself after the reason (only after a reload) | The browser pane was hidden: the page draws on animation frames, which a hidden pane pauses. The Notifier had applied the event (its count dropped by one) |
| The closed incidents left the page, and are not in line history | They started and ended on 15 Nov: closed incidents of earlier days leave the page, and history covers 2 days |
| After the restart the stale incident closed today was removed | Its end is 15 Nov; the clean-up removes closed incidents past the 2-day retention (with their reason), as decided |

A reason on a fresh incident (closed today) appearing on its closed row and in the line history is covered by the
tests, not seen live: the lab traffic is off, so no new incident occurred.
