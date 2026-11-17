# Design: fix-false-drops

## Context

See proposal.md. Facts from the code:

- **Partial today.** `AnswerTranslator` marks a point partial when `start + step > receivedMs`: only while the step's
  own 10 seconds are not over. Every later step is sent as finished.
- **The Emitter already resends a changed finished step.** Per line it remembers the finished steps it sent
  (`LinkedHashMap<ts, value>`, bounded by `maxSteps`) and sends a finished step again only when its value differs.
  So a late, complete count does reach Kafka and the Store, but by then the **Detector has already judged** the first,
  incomplete value: the false "traffic down".
- **The Detector never judges partial points** (add-detector: "Unfinished steps are not judged"), so marking the
  settling steps partial is enough on the Detector's side: no Detector code changes.
- **The error rule** in `detector.yml` matches tags `status_code=4*/5*`, `http.status_code=4*/5*`; the user's
  dashboard tags its counts `status`.
- **Detector tests read `../../config-repo/detector.yml`**, the local, git-ignored settings: they test whatever that
  machine's settings say, not the template that ships.
- **The page meter** shows `÷ normal/max(peak, 1e-9)` for drops, which gives "÷5,000,000,000×" for 0.

## Goals / Non-Goals

**Goals:** no anomaly from a value the source has not finished receiving; facts to tune the delay; errors judged
upwards only whatever the status tag is called; drops readable.
**Non-Goals:** fetching hours missed while the laptop was off (add-catch-up); changing how the Detector judges.

## Decisions

### F1. Settle delay: partial until the source has had time

```
partial  ⇔  start + step + settle-delay > receivedMs          (settle-delay: Collector setting, default 60 s)
```

| Step 13:04:10–13:04:20, answers at | Today | With 60 s |
|---|---|---|
| 13:04:25 (count 1) | finished → judged → "traffic down" | partial → not judged |
| 13:04:55 (count 9) | changed → resent, already judged | partial |
| 13:05:25 (count 13) | changed → resent | **finished → judged once, with 13** |

The Collector already fetches the last `recent-window` (2 minutes) every poll, so each step is asked about several
times while it settles; nothing extra is fetched. `settle-delay` must be shorter than `recent-window` (validator),
otherwise a step would leave the window before it is finished. O(1) per point.

**Cost:** an anomaly opens about `settle-delay` later (60 s). Latency-only panels could settle faster, but one simple
setting is clearer; it can become per-kind later if measurements show the need.

### F2. Late changes measured (the facts for F1)

In the Emitter, when a finished step comes back with a different value:

```
late = collectedMs − (ts + step)       → per source (site): count += 1, latest = late, max = max(max, late)
```

Health shows per source `lateChanges`, `latestChangeAfterSeconds`, `maxChangeAfterSeconds`. If changes keep arriving
after `settle-delay`, the delay is too short for that account; the health page says so in a warning
("eu: values still changed 85 s after their step: raise settle-delay"). O(1) per point, a few numbers per source.

### F3. Error counts by `status` too (settings)

`config-template/detector.yml`, rule `errors`: add `status=4*`, `status=5*` to `tags`. Settings only; the Detector
already matches tag globs (add-detector). Existing `config-repo`s keep their own file: the README's upgrade note says
to add the two patterns (one line).

Detector tests switch from `../../config-repo/detector.yml` to `../../config-template/detector.yml`, so they test what
ships and pass on a fresh clone before `setup.sh` has run.

### F4. Drops on the page

```
value 0            → "dropped to 0 (−100%)"
value < normal     → "−" + round((1 − value/normal) × 100) + "%"      e.g. 1 vs 13 → "−92%"
value ≥ normal     → unchanged ("12.6×")
```

The bar for a drop is the percentage itself (100% = full width), no logarithm needed.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Anomalies 60 s later | Measured trade; the late-change numbers show whether a shorter delay is safe for an account |
| A source slower than 60 s | Health warns with the measured delay; raise `settle-delay` (up to `recent-window`) |
| Partial points are sent more often (each poll while settling) | Already so for the open step; about 6 settling steps per line instead of 1, a few KB per poll |

## Migration Plan

1. Ship with `settle-delay: 60s` in the template and a default of 60 s when the setting is missing, so existing
   `config-repo`s get it without editing.
2. README upgrade note: add `"status=4*", "status=5*"` to the `errors` rule of `detector.yml`.

## Open Questions

None.
