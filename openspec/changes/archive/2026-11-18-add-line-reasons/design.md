# Design: add-line-reasons

## Context

See proposal.md. Facts from the code:

- `AffectedSeries.reason` (field 9) already exists and means the **direction** ("up", "down", "silent"). A person's
  reason for a line therefore needs another name.
- The incident book (`IncidentBook`) keeps each incident's lines as `Member`s; `resolve` (add-incident-resolution)
  closes a whole incident; `calmed` turns it CALM.
- Detector settings already hold lists by signal (`silence-skip-signals`, `group-by`).
- The page draws each line as a leaf of the incident's tree (add-incident-history) and has one reason box per incident.

## Decisions

### L1. Contract

```proto
message AffectedSeries  { … string explanation = 13; int64 explained_ms = 14; bool bau = 15; }
message ResolveIncident { … string series_id = 5; }      // "" = every line still needing a reason
```

`explanation` is the person's reason for that line ("BAU (business as usual)" for BAU lines). `Incident.reason` stays:
the summary when the incident closes. New fields only; the compatibility check passes.

### L2. BAU from settings

```yaml
anomaly:
  detector:
    bau:                                  # anomalies that are business as usual: no reason needed
      - { signal: general }               # any direction, including silent
      - { signal: errors, direction: down }
```

`DetectorSettings.bau` (missing → the two defaults above); each entry a signal and optionally a direction (`up`,
`down`, `silent`). Checked like the other rules; reloads without restart. A line is BAU when its signal and direction
match an entry when it joins (or rejoins) an incident: O(entries), two or three.

### L3. The book: reasons per line, closing by lines

| Event | Change | Cost |
|---|---|---|
| A line joins | `member.bau` from L2; BAU lines get explanation "BAU (business as usual)" | O(1) |
| A reasoned line spikes again | Its explanation is cleared (needs a new one) | O(1) |
| `resolve(incident, line or all, text)` | Sets the explanation (and time) on that line, or on every non-BAU line without one; announces an UPDATED change; then the closing check | O(lines) |
| Calm, or a line explained while calm | **Closing check**: every non-BAU line explained → CLOSED with the summary; else stays CALM | O(lines) |
| Summary (`Incident.reason`) | Distinct explanations of non-BAU lines: none → "BAU"; one → that text; n → "n reasons" | O(lines) |

An incident of only BAU lines passes the closing check as soon as it is calm: it closes by itself with "BAU".
("Every silent line is BAU" was asked for on 18 Nov and moved to its own change, `silent-is-bau`.)

### L4. Notifier and page

| Piece | How |
|---|---|
| `POST /incidents/{id}/reason` | Body `{"reason": "...", "line": "<series id>"}`; no `line` = every line still waiting |
| Leaf (line) | Explained: the text and time (display zones). BAU: dimmed, "BAU". Waiting: a small reason field and Send |
| Incident | "Same reason for all" (one field) when two or more lines wait; the incident-level box from add-incident-resolution goes |
| Feedback | `pending` keyed by `incident|line`; cleared when that line's explanation arrives |
| Notifications | Only when an opened incident has at least one non-BAU line |
| BAU-only incident | Row dimmed |
| History | A line's entry shows its own explanation; "BAU" for BAU; else the incident's reason (incidents closed before this change) |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Too much marked BAU hides a real problem | Only unrecognised ("general") lines and errors going down by default; a rule for a metric makes it recognised, and the list is a setting |
| More reasons to type | Only for non-BAU lines; "same reason for all" for one cause |

## Migration Plan

Deploy contracts, Detector and Notifier together. Incidents waiting before the change: their lines have no
explanation; BAU is decided when a line next joins, so existing waiting incidents may still ask for reasons on general
lines once; "same reason for all" closes them quickly.

## Open Questions

None.
