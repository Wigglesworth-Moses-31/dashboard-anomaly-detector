# Verification: fix-false-drops

17 Nov 2026. False "traffic down" from late data, errors tagged `status`, unreadable drops.

## Tests

| Suite | Result | What it proves |
|---|---|---|
| All modules | **240 run, 0 failed**, 23 skipped (live-only) | Nothing else moved; line ids unchanged (replay fixture) |
| `SettleDelayTest` | 4 passed | The live page's 13:04:10 case: count 1 at 13:04:25 and 9 at 13:04:55 sent **partial** (never judged), 13 at 13:05:25 sent finished, once; settle 0 gives the old behaviour (1 finished at once); a value changing 50 s after its step with settle 30 s is counted (1 change, 50 s); default 60 s; a settle delay longer than `recent-window` refused |
| `DetectorScenariosTest` (+2) | 52 passed in the Detector | `status=404` 13 → 0: no incident; `status=500` 2 → 40: "errors up". Detector tests now load `config-template/detector.yml` (what ships), not the local settings |
| Page meter | checked with the screenshot's values | 0 vs 5 → "dropped to 0 (−100%)"; 1 vs 13 and 130 vs 1,640 → "−92%"; a rise 1,213 vs 138 stays "8.8×" |

## Not verified here

| What | Why | Where it will show |
|---|---|---|
| Late data on a live account | The personal trial has no traffic now, so no step can arrive late | On the user's machine: Collector health `lateChanges`, `maxChangeAfterSeconds` per source; a warning when values change after `settle-delay` |
| The page in a browser | The meter function was run with the exact code of `app.js` and the screenshot's values, not in a browser | Next time the page runs |

## Upgrading

Existing `config-repo`s: add `"status=4*", "status=5*"` to the `errors` rule's `tags` in `detector.yml` (README,
*Upgrading an existing config-repo*). `settle-delay` needs no edit.
