# Verification: add-line-reasons

18 Nov 2026. Each line of an incident takes its own reason; one "same reason for all" covers every waiting line;
business-as-usual (BAU) lines need none.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **266 run, 0 failed**, 23 skipped (live-only) | |
| Contracts | passed | `explanation`, `explained_ms`, `bau`, `series_id` pass the compatibility check; baseline refreshed on purpose |
| Detector | 64 passed (38 scenarios) | One line's reason keeps a calm incident calm; the last line's reason closes it ("2 reasons"); one line's reason on an open incident only updates it; "same reason for all" closes an open incident at once and keeps earlier line reasons; a `general` line is BAU and a BAU-only incident closes by itself with "BAU" (no CALM); errors down is BAU, errors up is not; an explained line that spikes again loses its reason; an unknown BAU direction is refused; the older resolution scenarios unchanged |
| Notifier | 23 passed | `line` travels in the command, none means every waiting line, a bad line id → 400; a line's history shows its own reason, "BAU", or the incident's reason |

## Live (the real system; the waiting incidents on this machine are from 15 Nov)

| Check | Result |
|---|---|
| Page | Each waiting line shows a reason box; incidents with 2+ waiting lines also show "Same reason for all n lines (closes it)"; a single waiting line says "Give reason (closes it)" |
| One line of three (typed in the page) | The line shows "Reason: … · given 02:07 UTC · 07:37 IST · 21:07 EST (17 Nov)"; the incident stays open with "Same reason for all 2 lines" |
| Same reason for all | `202`, then CLOSED in the database within 3 s; stored lines: the latency line keeps its own reason, the other two carry the shared one; summary "2 reasons" |

## Notes

| Seen | Why it is right |
|---|---|
| The incidents waiting since 15 Nov still ask for reasons on silent latency/traffic lines | BAU is decided when a line joins (design: migration plan); and only `general` silence is BAU by default — silence of a recognised signal still asks |
| History did not list the closed 15 Nov incident | It ended more than 2 days ago: outside the history window |
