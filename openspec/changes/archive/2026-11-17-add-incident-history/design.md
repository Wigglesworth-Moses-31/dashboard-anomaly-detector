# Design: add-incident-history

## Context

See proposal.md. Facts from the code:

- **The group exists only in the Collector.** `PollUnit.View(dashboardId, dashboardTitle, panelTitle, path, filters)`
  holds the panel's group or section (`path`, add-dashboard-json J12), but `AnswerTranslator` copies only id, title
  and panel title into the contract `View`, so the group is lost on the way to the Detector.
- **Incidents already carry** per affected line: series id (the line's stable id), signal, reason, peak, peak time,
  normal, unit, `dashboard_title` (for a preset view: "Shop · Payments"), `panel_title`.
- **The Notifier** reads `incident-events` from the beginning at start (Kafka keeps 2 days), keeps today's board
  (latest event per incident) and streams it over RSocket. The page is plain JavaScript (`app.js`).
- **add-dashboard-view** (specs written, not built) planned a "group title" plus positions on `View`; the group title
  added here is the same thing, so that change will only add the positions.

## Goals / Non-Goals

**Goals:** the hierarchy on every incident line; nothing cut off; no links on the page; a line's last occurrence and
2-day history, fast, surviving restarts, ready to show reasons.
**Non-Goals:** entering reasons (add-incident-resolution), history beyond 2 days, charts.

## Decisions

### H1. Contract: one new field in two messages

```proto
message View           { ... string panel_path = 4;  }   // group or section of the panel, "" when none
message AffectedSeries { ... string panel_path = 12; }
```

New field numbers only: the compatibility check (add-contracts) passes; the baseline is refreshed on purpose and
`docs/contracts.md` updated. Old messages read as "".

### H2. Filled on the way

| Service | Change | Cost |
|---|---|---|
| Collector | `AnswerTranslator` sets `View.panel_path` from `PollUnit.View.path` | O(views) per line description |
| Detector | `IncidentBook.Member` copies the primary view's `panel_path` into `AffectedSeries.panel_path`, like `panel_title` today | O(1) |

A changed description (new group) reaches the Detector as any description change does; line ids do not change (the
group is not part of the id).

### H3. The page: a small tree per incident

```
Shop · Payments                                   ← dashboard_title (view, with its saved view)
  API Gateway Metrics                             ← panel_path (absent: this level is skipped)
    Request Counts by Status Code                 ← panel_title
      status=200      37  (normal 670, −94%)  ▸   ← group tags, value, normal, change (fix-false-drops F4)
      status=404       1  (normal 13, −92%)   ▸
```

| Choice | Why |
|---|---|
| Lines grouped by view → group → panel (a `Map` per level in first-seen order), built once per incident change | An incident across three panels reads as three short trees, not a flat list; O(lines) |
| Full text with CSS `overflow-wrap: anywhere`, no `text-overflow: ellipsis` | Long `api=/...` paths and titles are shown whole and wrap |
| Links removed from rows (they stay in the incident and in the report) | User decision, 17 Nov |
| The title line still shows the incident's title, state and times | Unchanged |

### H4. A line's past: a 2-day index in the Notifier

Built from the incident events the Notifier already reads; no database, no new service.

```
index: Map<lineId, LinkedHashMap<incidentId, Occurrence>>        Occurrence = start, end, peak, normal, unit, reason
on every incident event:  for each affected line → put(incidentId, occurrence)      latest event wins: O(lines)
every 10 minutes:          drop occurrences that ended more than 2 days ago           O(total), a few thousand
GET /history?line=<id>&before=<incidentId>  → { last, list (newest first) }           O(k) for the line's k entries
```

| Concern | Choice |
|---|---|
| Order | Entries kept in start order (events arrive per incident in order; a sort of the line's few entries on read, O(k log k), is simpler and k is small) |
| Memory | About 64 bytes per occurrence; a busy lab day (~800 incidents × ~3 lines) is under 200 KB for 2 days |
| Restart | The Notifier re-reads 2 days of events at start, so the index is rebuilt before the page connects |
| Reasons | `reason` is read from the event when present (a new field in add-incident-resolution); "—" until then |
| Transport | A plain HTTP GET (`/history`) next to the page, JSON; the RSocket stream stays for live changes only |

### H5. Data structures, algorithms and patterns

| Need | Choice | Cost |
|---|---|---|
| Group on the way | One contract field, copied | O(1) |
| Page tree | Map per level, insertion order | O(lines) per incident |
| Line history | Hash map line → ordered map incident → occurrence; periodic sweep | O(lines) per event, O(k) per read, O(n) per sweep |
| Patterns | Read model (the Notifier's index is a projection of the event stream, CQRS-style), Latest-wins by incident id | — |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| History limited to 2 days | Stated on the page; longer history would read the Detector's database (later, if wanted) |
| Lines that changed id (a query edited in the dashboard) have separate histories | Expected: a different question is a different line |
| Incidents saved before this change have no group | The tree skips the empty level |

## Migration Plan

Deploy contracts, Collector, Detector and Notifier together (`scripts/system.sh` restarts all). Old events still
render (no group level).

## Open Questions

None.
