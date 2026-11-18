# Design: silent-is-bau

## Decisions

### B1. Any signal

```yaml
bau:
  - { signal: general }
  - { signal: errors, direction: down }
  - { signal: "*", direction: silent }    # "*" = any signal
```

`BauSettings.matches`: `"*"` matches every signal. O(entries), three.

### B2. Closing silent-only incidents at once

| Event | Change | Cost |
|---|---|---|
| A line joins or rejoins | BAU marked as today (add-line-reasons L3) | O(1) |
| After marking, the incident's lines are all BAU and all silent | CLOSED at once with "BAU" (a silence that never ends never becomes calm) | O(lines) |

### B3. Marking waiting incidents again

| When | How | Cost |
|---|---|---|
| The BAU list loads (start, or a commit) | Every member of every open or calm incident is marked again: one that turns BAU gets "BAU (business as usual)", one that stops being BAU loses it; then the closing check (B2 and the calm check) per incident | O(waiting lines) |
| An incident is restored after a restart | The same, when the list is already loaded | O(its lines) |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A real outage (a service stops sending) closes as BAU | The user's call (18 Nov). The line still appears, dimmed, with "BAU", and the history keeps it; removing the `*` entry restores reasons, without restart |

## Migration Plan

Deploy the Detector. Existing machines: add the third entry to `bau:` in `detector.yml` if that block was copied;
when `bau:` is missing the new default applies.
