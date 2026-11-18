# Design: add-settings-page

## Context

See proposal.md. Facts from the code (18 Nov 2026):

| Fact | Where |
|---|---|
| Regions and their dashboards are `anomaly.collector.sources` in `collector.yml`, a hand-written file full of comments | `config-template/collector.yml` |
| The Config Service serves files from `config-repo` commits (Spring Cloud Config, `refresh-rate: 0`); a `post-commit` hook calls `/actuator/busrefresh`, and every service reloads | `config-service`, `platform/install-config-hook.sh` |
| Keys reach only the Collector: `system.sh` exports `DD_*` from `.env` into that one process at start; `DatadogCredentials` reads them from the environment once per lane | `scripts/system.sh`, `DatadogCredentials` |
| The Collector rebuilds only lanes whose `LaneConfig` changed; `LaneConfig` holds key *names*, not values | `CollectorSettings`, `CollectorEngine` |
| Dashboard files are parsed and checked by `DashboardFiles` (all-or-nothing) and planned by `WatchedView`, `PollPlan`, `Batch`; `CheckDashboards` asks each panel once | `collector` |
| The page is served by the Notifier on :8104 (HTTP) and :7104 (RSocket); every service listens on all interfaces | `notifier` |

## Decisions

### S1. Who does what

| Service | Owns | Why there |
|---|---|---|
| **Collector** | Keys: checks them with Datadog, writes and removes them in `.env`, reads them on reload. Checks an uploaded dashboard (parse, panels, views, diff, ask each panel once) | It is already the only process that holds keys and talks to Datadog; it has the parsing code. Keys never pass through another service's memory except the Notifier's proxy (S6) |
| **Config Service** | Writes to `config-repo`: region entries, dashboard files; one commit each; then the bus refresh | It already owns the settings repository |
| **Notifier** | The Settings tab and one local proxy (`/settings/**`) to the two above | The page talks to one address |

### S2. Regions move to their own file: `regions.yml`

Rewriting `collector.yml` from code would lose its comments. So the page owns a separate file:

```yaml
# config-repo/regions.yml — written by the settings page (one commit per change). Hand edits work; comments here are not kept.
anomaly:
  collector:
    sources:
      - name: us
        site: datadoghq.com
        api-key-env: DD_US_API_KEY
        app-key-env: DD_US_APP_KEY
        dashboards:
          - { file: dashboards/us/shop.json, presets: all }
```

The Collector asks for both files: `spring.cloud.config.name: collector,regions` (the later name wins; to be confirmed
by a test in task 1.1). `collector.yml` keeps everything else, with its comments.

**One-time move** (existing machines): on the first write, if `collector.yml` still has `sources:`, the Config Service
cuts that block out as text (from the `sources:` line to the next line at the same or lower indent), writes it to
`regions.yml`, re-parses both and checks the resulting `sources` are **equal** to before; only then commits
("settings page: move regions to regions.yml"). If not equal, nothing is written and the page says how to move it by
hand. `setup.sh` writes `regions.yml` for new machines.

### S3. Writes: one lock, one commit, no lost updates

| Step | How | Cost |
|---|---|---|
| Serialise | One `ReentrantLock` in the Config Service: one write at a time | O(1) |
| Lost-update guard | The page sends the commit id it was shown (`If-Match`); a different `HEAD` → `409 "changed meanwhile, reload"` (optimistic concurrency) | O(1) |
| Hand edits | JGit status of `regions.yml` and the touched file: uncommitted change → `409 "commit or discard your edit first"` | O(files touched) |
| Write | Temp file + atomic rename, then `git add` + `commit` by JGit on the working copy (`config-repo`), author "settings page" | O(file size) |
| Announce | JGit does not run hooks, so the Config Service calls the bus refresh itself after the commit | one call |
| Limits | At most 2 regions; region name `us` or `eu`; site fixed by name; file ≤ 5 MB; file name `dashboards/<region>/<slug>.json` | checked first |

Nothing is pushed (decided 17 Nov).

### S4. Keys

| Step | How |
|---|---|
| Check | `POST /keys/check` on the Collector: the API key with `GET /api/v1/validate`, the application key with one tiny metrics query (`/api/v1/query`, last minute) on the region's site. 403 → "Datadog refused the application key"; Datadog's own error text is never passed on (it may echo input) |
| Write | `.env` is rewritten whole: other lines and comments kept, the two `DD_<REGION>_*` lines replaced or appended; temp file with mode `600`, then atomic rename |
| Read | The Collector reads `.env` (path from `LAB_ENV_FILE`, set by `system.sh`) on every settings reload. `.env` wins; an environment variable is the fallback (tests, CI). `system.sh` stops exporting `DD_*` |
| Notice a change | `LaneConfig` gains a **key fingerprint** (SHA-256 of the two values, never logged): new keys → that lane alone is rebuilt, O(1) compare |
| Never leaked | Keys are held in a `Secret` type whose `toString()` is `***`; request logging is off for these paths; errors carry no request body; the page's key fields are `type=password`, `autocomplete=off`, cleared after sending |

Add a region: check keys → write `.env` → commit the entry → refresh → lane starts. Remove: commit (lane stops) →
remove keys from `.env`. A failure after `.env` is written leaves unused keys only; the page says which step failed.

### S5. Dashboards

| Operation | How | Cost |
|---|---|---|
| Check (upload) | Collector `POST /dashboards/check` (multipart, ≤ 5 MB): `DashboardFiles` parses; answers title, panels by group, variables, saved views; with an existing file, the **diff**: panels and views added or removed (two hash sets) | O(widgets) |
| Ask each panel once | Optional; through the region's lane (its token bucket and 429 gate), batched like a poll; answers lines per panel | the batch's calls (≈ panels / 40) |
| Create / update | Config Service writes the file and its entry in `regions.yml`, one commit | O(file) |
| Delete | File and entry removed, one commit; the lane drops its views at reload | O(1) |
| Read / download | Config Service serves the file from the commit (the existing `/files` endpoint) | O(file) |

### S5b. The JSON editor (user, 18 Nov)

| Part | How | Cost |
|---|---|---|
| Editor | A `<textarea>` with a line-number gutter kept in step on scroll; monospace; Tab inserts two spaces; no library (nothing loaded from the internet) | O(lines) per redraw |
| Mistakes while typing | `JSON.parse` after typing stops for 300 ms; the error position is turned into line and column; Save is off while invalid | O(text) per pause |
| Format | `JSON.stringify(parse(text), null, 2)`; Undo all puts back the committed text | O(text) |
| Check | The same `/dashboards/check` with the edited text and `current` = the file: summary and diff | as S5 |
| Save | The same write as Update (`replace: true`, no `presets`, so the entry's watched views and filters stay); commit message "settings page: edit us/shop.json" | as S3 |
| Leaving with unsaved edits | "Discard your edits?" before closing the editor | O(1) |

### S6. Local only

`server.address: 127.0.0.1` for the Config Service, Collector and Notifier (HTTP and RSocket). The Notifier's proxy
forwards `/settings/**` to `localhost` only, streams bodies without logging, and strips nothing else.

### S7. The Settings tab

| Part | Shows |
|---|---|
| Regions | Name, site, keys ✓/✗ and valid ✓/✗ (from the Collector's health), "Replace keys", "Remove"; "Add region" only while fewer than two |
| Dashboards per region | Title, file, watched views (all / n of m / none), panels; Open, Download, Replace, Delete |
| Upload | File picker → check result (title, panels by group, views with checkboxes, diff when replacing) → optional "Check panels" → Add / Update |
| Feedback | "saving…", then "saved (commit abc123)" or the reason it was refused |

Plain HTML and the page's existing style; no framework.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A key typed into a browser page | Local-only binding; password fields; one hop to `.env`; never echoed; the alternative (editing `.env` by hand) stays possible |
| Two writers (page and a hand edit) | Hand edits refused until committed; `If-Match` on the commit id |
| The one-time move cuts the wrong lines | Equal-after-parse check before committing; otherwise nothing is written |
| Spring merging two files differently than expected | A test (task 1.1) proves `regions.yml` wins and `collector.yml` without `sources` still loads |

## Migration Plan

Deploy all services together. Existing machines: the first page write moves `sources` to `regions.yml` (S2);
`system.sh` passes `LAB_ENV_FILE` instead of `DD_*`. Rollback: revert the commits in `config-repo` and start the
previous build.

## Open Questions

None.
