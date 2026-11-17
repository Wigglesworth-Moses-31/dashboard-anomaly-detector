# Design: add-display-time-zones

## Context

See proposal.md. Facts from the code:

- `SharedSettings(timeZone, environment)` in `service-kit` is read by every service; `time-zone` decides the page's
  day and the Reporter's windows.
- The page builds one `Intl.DateTimeFormat` for the shared zone when the snapshot's end line says
  `"time_zone": …`, and re-snapshots when the zone changes (add-notifier).
- The Reporter formats times with `DateTimeFormatter.withZone(window.zone())`.
- **Zone names differ by locale** (measured in the page's JavaScript engine): India is "GMT+5:30" in US English and
  "IST" only in Indian English; New York is "EST/EDT" only in US English; UTC is "UTC" everywhere. Java's
  `DateTimeFormatter` pattern `z` with English gives UTC, IST, EST/EDT directly.

## Decisions

### Z1. One setting, shared by every service

```yaml
anomaly:
  shared:
    time-zone: UTC                                   # the day (page, daily report)
    display-time-zones: [UTC, Asia/Kolkata, America/New_York]   # how times are written; the first is the main one
```

`SharedSettings` gains `displayTimeZones` (missing → the default above). Validators: 1–4 entries, each a valid zone
id. It is part of the existing shared settings, so it reloads without restart like `time-zone`.

### Z2. The page: formatters per zone, cached, labels that read well

On the snapshot's end line the Notifier now also sends `"display_zones": [...]`; a change of the list re-snapshots,
exactly like a change of `time_zone`. The page builds, once per list:

| Per zone | How |
|---|---|
| Time formatter | `Intl.DateTimeFormat(…, {timeZone, hour, minute, second, hourCycle: "h23"})` |
| Date formatter (to spot another calendar day) | `en-CA` year-month-day, compared with the main zone's |
| Label | The first of `en-US`, `en-IN`, `en-GB`, `en-AU` whose short zone name contains no "+"/"−" (UTC, IST, EST/EDT, BST…); otherwise the offset ("GMT+5:30"). Computed per date, so EST ↔ EDT follows the date |

`time(ms)` becomes `13:04:10 UTC · 18:34:10 IST · 08:04:10 EST`; a zone on another calendar day adds
` (18 Nov)`. Formatters are created once per zone list (cheap per call); labels are cached per zone and calendar day
(they change at most twice a year), so a page of 600 rows still formats in a few milliseconds.

Short forms (the closed list, history lines) use hours and minutes: `13:04 UTC · 18:34 IST · 08:04 EST`.

### Z3. Reports: one shared helper

`service-kit` gains `Times.format(Instant, List<ZoneId>, boolean seconds)`: the same text as the page, with Java's
English zone names (`z`). The Reporter uses it for every time; `add-reports-on-demand` builds on it.

### Z4. UTC as the default day

`config-template/application.yml`: `time-zone: UTC` and `display-time-zones` (no longer filled by setup);
`setup.sh` stops asking for a time zone (UTC; changeable later in `application.yml`), but still accepts `TIME_ZONE`
unattended. Existing `config-repo`s: README upgrade note.

## Data structures, algorithms and patterns

| Need | Choice | Cost |
|---|---|---|
| Format a time | Cached formatters per zone; label cache per (zone, day) | O(zones) per time |
| Another day | Compare the zone's date with the main zone's | O(1) |
| Pattern | One shared format on both sides (page and report): same text everywhere | — |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| Longer time texts on narrow screens | They wrap; durations stay short |
| A zone whose name no locale abbreviates | The offset ("GMT+5:30") is shown, still correct |

## Migration Plan

Existing `config-repo`s: set `time-zone: UTC`, add `display-time-zones`. Without the line, the default list applies.

## Open Questions

None.
