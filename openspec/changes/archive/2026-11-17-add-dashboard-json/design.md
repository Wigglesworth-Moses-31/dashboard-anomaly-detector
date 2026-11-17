# Design: add-dashboard-json

## Context

See proposal.md for the why. Facts from the code that shape the design:

- **One parser already exists.** `DatadogDashboardReader.read(JsonNode)` turns Datadog's dashboard JSON into the
  neutral `Dashboard` (an iterative depth-first walk, O(W) widgets). It reads `id`, `title`, `template_variables`
  (`default`, or the first of `defaults`) and every widget. The API answer and the exported JSON have the same
  shape (both test files in `src/test/resources/dashboards/` parse: one is an API answer, one an export without
  `id`), so a file needs **no new parser**.
- **Line ids never depended on how a dashboard was found.** `SeriesIds` = SHA-256 over system, site, query id,
  tags; the query id comes from the filled question. Same file + same filters ⇒ same questions ⇒ same ids.
- **Filling is already right.** `FilterFiller.values(variables, fromSettings)` takes settings first, then the
  dashboard default; `PollPlan.build` fills and merges questions per source.
- **Settings reload** is triggered by `EnvironmentChangeEvent` keys under `anomaly.collector.`; a commit that only
  changes a JSON file changes no property, so it would not reload by itself. The Spring Cloud Config client sets
  `config.client.version` to the **commit id** of what it loaded, which changes with every commit.
- **Recordings store parsed dashboards** (`dashboards.jsonl` holds the neutral `Dashboard`, by id), not Datadog's
  JSON; replay already has every definition it needs.
- The Config Service (Spring Cloud Config, Git backend) can already serve **plain files** from `config-repo`:
  `GET /{application}/{profile}/{label}/{path}`.

## Goals / Non-Goals

**Goals:** dashboards only from files; filters visible in the file and overridable; a pre-run check that runs the
Collector's own code; empty panels reported while running; file changes live and atomic with the YAML; old line
ids kept; old recordings still replay.

**Non-Goals:** reading dashboards from the API at runtime, drift detection against Datadog, different `filters:` per
preset.

## Decisions

### J1. Settings: a dashboard is a file reference

```yaml
sources:
  - name: us
    site: datadoghq.com
    api-key-env: DD_US_API_KEY
    app-key-env: DD_US_APP_KEY
    dashboards:
      - file: dashboards/us/shop.json        # in config-repo; exported from Datadog
        id: abc-def-ghi                      # optional: for links, when the export has no id
        filters: { service: payments }       # optional: wins over the file's default for that variable
      - file: dashboards/us/platform.json
```

`DashboardRef(file, id, filters)` replaces the plain id string. A string (an old id) is still **bound** (a small
`Converter<String, DashboardRef>` makes `DashboardRef(null, id, {})`) so that the validator can refuse it with a
clear message instead of a binding error:

> `sources[0] (us): dashboards[0] 'abc-def-ghi' is an id; dashboards are now JSON files. Export it in Datadog
> (Share → Export dashboard JSON) into config-repo/dashboards/us/ and list it as '- file: dashboards/us/<name>.json'`

| Rule | Why |
|---|---|
| `file` required, relative, under `dashboards/`, ending `.json`, no `..` | Only files of the settings repository; no reading of arbitrary paths |
| A file once per source | As the id rule before |
| `filters` keys must be variables of that dashboard (checked after loading, J3) | A typo like `envv` would silently do nothing |
| In `source: replay`, `dashboards` is ignored | Replay plays the definitions inside the recording (J7) |

The old single-site form keeps working with file references (`datadog-site` + `dashboards`), since `lanes()` maps
it to one source; only ids are refused.

### J2. Getting the files: from the Config Service, like every other setting

**Found live (17 Nov):** Spring Cloud Config's own plain-file endpoint (`/{app}/{profile}/{label}/{path}`) answers 404
when the project path contains spaces: it compares an encoded and a raw path. `basedir` does not help (a local `file:`
repository is used in place) and a symbolic link is refused. So the Config Service has a small endpoint of its own,
`GET /files/dashboards/**.json`, that reads the file from the last commit of `main` with JGit (already a dependency):
committed versions only, `dashboards/**/*.json` only, never `..`. The Collector fetches `{config-uri}/files/{file}`.

The Collector fetches each file over HTTP from the Config Service it already uses:
`GET {config-uri}/collector/default/main/{file}`. One code path for local and any later setup; no second way
into `config-repo`.

| Concern | Choice | Cost |
|---|---|---|
| Many files | Fetched in parallel on virtual threads, 5 s timeout each | Wall time ≈ the slowest file |
| Unchanged files | A cache keyed by **(path, SHA-256 of content)** → parsed `Dashboard` | Parse only what changed: O(changed widgets) |
| Memory | The cache holds only files in the current settings (others evicted on apply) | A parsed dashboard is a few KB |

### J3. Loading is part of accepting the settings (atomic)

New YAML and new files are accepted **together or not at all**:

```
commit ──► config.client.version changes ──► SettingsHolder.reload()
                 └─ validators: YAML rules (J1) ──► DashboardFiles.load(all refs): fetch, parse, check
                         ok:  new snapshot (YAML + parsed dashboards) replaces the old one in one step
                         any problem: whole version refused; old YAML and old dashboards keep running;
                                      /actuator/settings → lastRejected names the file and the problem
```

- `SettingsHolder` also reloads when `config.client.version` changes (a small, generic addition in
  `service-kit`: "reload on these extra keys"), so a JSON-only commit is noticed.
- The loader runs as one more validator, created in `CollectorSettingsConfig` with access to `DashboardFiles`.
  Validators stay pure for YAML; this one is the single place with I/O, and it is idempotent (cache).
- File checks: valid JSON; has `widgets`; at least one panel with a metric question **or** a note that none was
  found (health, not refusal: a dashboard of logs-only widgets is legal but useless); `filters` names ⊆ the file's
  variables (message lists the variables).
- The engine reads parsed dashboards from the accepted snapshot; `Discovery` takes them from there instead of
  calling `MetricSource.dashboard(id)`. A lane's "dashboards changed" path (add-multi-site M7) is unchanged.

**Why not re-read on a timer?** Files only change by commit; the commit already broadcasts. The 60-minute
`dashboard-refresh` stays only for the replay path and is otherwise unused.

### J4. View ids and links

| File has `id` | `id:` next to it | View id in line descriptions | Links |
|---|---|---|---|
| yes | — | file's id | yes |
| no | yes | that id | yes |
| yes | yes, different | refused: "ids differ" | — |
| no | no | `file:us/shop.json` | none; health "no id for links" |

The Detector already builds links only when the view id is a real dashboard id; it skips ids starting with
`file:`. Grouping by dashboard (lines with no `group-by` tag) uses the view id, so it stays stable as long as the
file and id stay.

### J5. Panels that return nothing

`PollProcessor.process` already returns the number of lines in an answer. The engine keeps
`lastLines: Map<unitId, Integer>` (O(1) per poll). Health groups it per source → dashboard → panel:

```
panel empty  ⇔  every unit showing that panel returned 0 lines in its latest answer
dashboard empty  ⇔  every panel of it is empty   ──► DEGRADED, warning names source, dashboard, filters
```

O(units) per health read (a few hundred). The warning carries the filled filters (`env:prod, service:*`) because
that is what the user can change.

### J6. The check before running: the Collector's own code, without the Collector

`scripts/check-dashboards.sh [source]` runs a small `main` inside the Collector's jar
(`lab.anomaly.collector.check.CheckDashboards`, started through Spring Boot's `PropertiesLauncher`), so it uses the
**same** `DatadogDashboardReader`, `FilterFiller`, `PollPlan`, `DatadogSource` and `AnswerTranslator` as the running
Collector. No Kafka, no database, no Config Service needed.

| Reads | From |
|---|---|
| `collector.yml` and the JSON files | The `config-repo` **working tree**, so you can check before committing |
| Keys | `.env`, only `DD_*_KEY` names (as `system.sh`) |

For each unit: one timeseries call over the last 15 minutes, through a token bucket at a small fixed rate (2 per
second) so a check never competes with a running Collector. Output per dashboard, panels in dashboard order:

```
us · Shop (dashboards/us/shop.json)   filters: env:prod, service:*
  ✓ Latency            3 lines   avg:latency{env:prod}.rollup(max, 10)
  ✗ Errors             0 lines   sum:errors{env:prod,status:5xx}.rollup(sum, 10)
  11 of 12 panels returned lines
```

Exit code 1 when any dashboard returns no lines at all, so `setup.sh` can stop on it.

### J7. Replay and old recordings

In `source: replay`, every dashboard **recorded** for a source is played, as recorded; `dashboards` in settings is
ignored. Old recordings (made by id) therefore replay unchanged, and their line ids stay the published ones (the
existing fixture test keeps proving it). Recording itself (`recording.enabled`) keeps writing the parsed
dashboards it used, now from files.

### J8. Setup and the export helper

| Script | Does |
|---|---|
| `setup.sh` | Per source asks for JSON file paths (several at once; drag-and-drop paths work), copies them to `config-repo/dashboards/<source>/<file name>`, writes `- file:` entries, and runs the check (unless `--no-check`). Unattended: `DASHBOARD_FILES_<NAME>="a.json b.json"` |
| `export-dashboards.sh <source> <id>...` | Read-only `GET /api/v1/dashboard/{id}` with that source's keys (headers file, never argv), saves pretty JSON to `config-repo/dashboards/<source>/<id>.json`. For people with `dashboards_read`; prints the `- file:` lines to paste |

### J10. Presets: one view per saved view (user, 17 Nov: all presets)

```yaml
dashboards:
  - file: dashboards/us/service-overview.json
    id: abc-def-ghi          # from the dashboard URL, for links
    presets: all             # or [Payments, Billing]; absent = the file's defaults
```

| Step | How | Cost |
|---|---|---|
| Values of a view | Start from the file's defaults, apply the preset's variables (a variable it does not set keeps its default), then `filters:` | O(variables) per view |
| One view per preset | View id `<dashboard id or file>#<preset name>`, title "Service Overview · Payments" | — |
| Plan | `PollPlan` fills every panel per view; questions are merged by their canonical text **within the source** (existing hash map), so a question two presets share is asked once and its unit lists both views | O(views × panels) |
| Unknown names | Refused with the list of the dashboard's presets (part of J3's loading check) | — |

Measured on the rebuilt Service Overview file (17 Nov): 12 panels × 23 presets = 276 requests, **270 after merging**;
every 15 s that is 18 per second.

Line ids do not change: they come from the filled question, site and tags. A question shared by two presets is one
line shown under both views.

### J11. Several values: Datadog's boolean form

`FilterFiller` today writes items joined by commas (`{team:x,env:prod,!api:/health}`). When **any** variable of a
view has several values, the whole filter is written in boolean form, as Datadog's own page does:

```
{$team,$service,$env,!api:/health}  with team=[web,mobile], service=payments, env=prod
  → {team IN (web,mobile) AND service:payments AND env:prod AND NOT api:/health}
```

| Item | Becomes |
|---|---|
| `$var` with one value | `prefix:value` |
| `$var` with several values | `prefix IN (v1,v2)` |
| `$var` with `*` | dropped |
| `!tag:value` | `NOT tag:value` |
| other items | as they are |
| joined | ` AND ` |

With single values only, the comma form stays exactly as today, so **existing questions and line ids are unchanged**.
The boolean form is tried against the personal trial (read-only) before it is relied on.

### J12. Section headings

`DatadogDashboardReader` walks top-level widgets in order. When it meets a group **with no widgets**, it remembers
its title as the current section; following top-level panels get that section as their `path`, until the next group
(empty or not). A non-empty group still names its own panels, as today. O(W), same walk.

Panel paths are not part of questions or line ids; only names in views, health and the check change.

### J13. Budget guard: slow down instead of being refused

add-multi-site M8 warns above 90% of a source's allowed rate. With all presets, a smaller account could be over
100%. Instead of waiting for 429s, a lane **stretches its own poll interval**:

```
interval(lane) = max(poll-interval, calls ÷ (0.9 × allowed per second)   (calls = batches, J14))
```

| Example | Result |
|---|---|
| 270 units, allowed 24/s (300 per 10 s × 0.8) | 12.5 s < 15 s → stays 15 s |
| 270 units, allowed 8/s (100 per 10 s × 0.8) | 37.5 s → polls every 37.5 s; health says "eu: polling every 38 s to stay within the rate limit" |

No data is lost: every poll fetches the last `recent-window` (2 minutes) in 10-second steps, so as long as the
stretched interval stays below the window, every step still arrives, a little later. Above the window, health turns
`DEGRADED` ("too many panels for this account: steps may be missed"). O(1) per plan; the scheduler already
supports a per-unit interval change (keys keep their timing).

### J14. Batching: many panels in one Datadog call

**Found live (17 Nov):** taking units "due within the next 2 s" on every scheduler pass (every ~200 ms) made batches
of one or two (122 calls for 146 polls). A batch now starts only when the earliest unit is actually due, and takes
everything due within the next 2 s: 24 calls for 144 polls.

Measured on the personal trial (17 Nov, read-only): one `/api/v2/query/timeseries` request with 1, 5, 10, 20, 40 and
80 queries + formulas all answered `200`, every series tagged with its formula index, and each request cost **one**
unit of the 300-per-10-s limit (1.0 s for 1 formula, 1.2 s for 20, 1.5 s for 40, 2.9 s for 80).

| Step | How | Cost |
|---|---|---|
| Group due units of one lane | When the scheduler hands out due units, the lane packs them into batches of at most `batch-formulas` (default 40) formulas, in due order | O(due) |
| Rename to keep them apart | Unit *u*'s `query1` becomes `u7_query1` inside its formulas (word-boundary rewrite); formula offsets are kept per unit | O(formula length) |
| Map answers back | Series with formula index *i* go to the unit whose offset range holds *i*, as its own index *i − offset* (binary search over offsets) | O(log units) per series |
| Unchanged ids | Each unit is translated with its own key and own formula index, exactly as before | Line ids identical |
| One failing question | Datadog rejects the whole request (`400`): the batch is split in two and retried (bisection) until the bad unit is alone; its breaker counts, the others succeed | O(log batch) extra calls, only on failure |
| Same window | Units in one batch share `from`/`to`; a unit polled for the first time (backfill) goes in its own batch | — |

With all Service Overview presets (270 units, about 400 formulas): about **10 calls per 15 s instead of 270**.

### J9. Data structures, algorithms and patterns

| Need | Choice | Cost |
|---|---|---|
| Walk a dashboard's widgets | Iterative DFS with an explicit stack (existing) | O(W) |
| Skip unchanged files | Cache by (path, SHA-256) | O(file size) to hash, parse only on change |
| Apply YAML and files together | Snapshot swap after full validation (all-or-nothing) | One reference write |
| Empty panels | Hash map unit → last line count; grouped on read | O(1) per poll, O(units) per health read |
| Fetch files | Parallel virtual threads | ≈ slowest file |
| Check rate | Token bucket (existing class) | O(1) per call |
| Presets | Fill per view, merge equal questions by hash map | O(views × panels) |
| Several values | Rewrite one filter into boolean form only when needed | O(items) |
| Headings | Same DFS, one "current section" variable | O(W) |
| Budget guard | Interval = max(set, units ÷ (0.9 × allowed)) | O(1) per plan |
| Batching | Pack due units up to 40 formulas, rename queries, map series back by binary search over offsets, bisect on a 400 | O(due) per round, O(log n) per series |
| Patterns | Ports and Adapters (files are just another way to get a `Dashboard`), Validator, Snapshot (immutable settings + dashboards), Cache-aside (parsed files) | — |

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| The file goes stale when the dashboard changes in Datadog | Documented: re-export and commit. Out of scope to detect |
| An export with secrets in queries or notes | Dashboard JSON holds queries and titles, not keys; it lives in your local `config-repo` (git-ignored in the project repo) |
| A large JSON slows a reload | Parse only changed files; JSON of 500 widgets parses in milliseconds |
| `config.client.version` missing (no Git backend) | Fall back: reload compares the files' SHA-256 every `dashboard-refresh` |
| Breaking for anyone using ids | Clear refusal message, the export helper, README steps |

## Migration Plan

1. Build and test with the lab's two dashboards as files (export them from the trial with
   `export-dashboards.sh`, read-only), and check that line ids equal those collected by id.
2. In your `config-repo` (currently replay mode): nothing to change for replay. For live mode, export the two
   dashboards and switch `dashboards:` to `- file:` entries (one commit).
3. On the other computer: export the dashboard JSON from Datadog, run `setup.sh` (or `check-dashboards.sh` after
   editing), fix filters until every panel shows lines.

## Open Questions

None.
