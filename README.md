# Dashboard Anomaly Detector

Watches your Datadog dashboards every 15 seconds, in one or several Datadog regions at once (for example US and EU), catches **short spikes** that monitors miss or report late,
groups them into **one incident per problem**, shows them on a **live page** within seconds, and writes a
**Markdown report** per day. You bring a Datadog account and the dashboards you already have; nothing is created or
changed in Datadog.

```
                        ┌──────────────────────── Config Service (settings from config-repo/, live reload) ───────────┐
Datadog dashboards ──► Collector ──► Kafka ──► Store (TimescaleDB, 2 days)        ◄── gRPC ──┐                        │
   (read-only API)        metric-series                                                      │                        │
                          metric-points ──► Detector ──► incidents ──► Kafka incident-events ├──► Notifier ──► live page (RSocket)
                                                                                             └──► Reporter ──► reports/<day>.md
```

| Service | What it does | Port |
|---|---|---|
| Config Service | Serves every setting from `config-repo/`; a commit there reaches all services in seconds | 8888 |
| Collector | Reads each dashboard's panels from Datadog, then polls their values at 10-second steps | 8101 |
| Store | Keeps every value once (TimescaleDB), per-minute summaries, answers history questions (gRPC 9102) | 8102 |
| Detector | Learns each line's normal (sliding median), spots spikes, drops and silence, groups them into incidents | 8103 |
| Notifier | Live dashboards per region with anomalies (RSocket on 7104, values over SSE), Settings | **8104** |
| Reporter | One Markdown report per window (a day by default), then cleans the reported raw values | 8105 |

Java 25, Spring Boot 4, Kafka 4 (KRaft), TimescaleDB on Postgres 17, gRPC, RSocket, Protobuf. Every setting lives
outside the code. The design, every requirement and how each was verified are in `openspec/` and `docs/`.

---

## What you need

| What | Notes |
|---|---|
| One or more Datadog accounts and their **sites** | `datadoghq.com` (US1), `datadoghq.eu` (EU), `us3.datadoghq.com`, `us5.datadoghq.com`, `ap1.datadoghq.com`... One account per region; each is a **source** |
| Per source, a Datadog **API key** | Organization settings → API keys |
| Per source, an **application key** with read scopes | Organization settings → Application keys. Scopes: `timeseries_query`, `metrics_read` (plus `dashboards_read` only for the optional export helper). **Both keys are needed**: the API key says which account, the application key what may be read |
| Each dashboard as an **exported JSON file** | In Datadog: open the dashboard → **Share → Export dashboard JSON** (or ⚙ → Export). Save the `.json` file. Its saved views (presets) and variable defaults decide the filters |
| **Docker** with Compose v2 | Docker Desktop, Colima, or Docker Engine. About 1.5 GB of memory for Kafka and the database |
| **Java 25** | e.g. `brew install openjdk@25`, `sdk install java 25-tem`, or your package manager. `java -version` must say 25 |
| git, curl, python3 | Usually already there |

Maven is **not** needed: the repository includes the Maven wrapper (`app/mvnw`).

The system only **reads** from Datadog: dashboard definitions, metric metadata and timeseries queries. It uses about
8% of Datadog's query rate limit for two dashboards, slows itself down on a 429, and shows that on its health page.

## Set up (once)

```bash
git clone https://github.com/<you>/dashboard-anomaly-detector.git
cd dashboard-anomaly-detector
scripts/setup.sh
```

`setup.sh` asks for each source, then does the rest:

| It asks, per source | It does |
|---|---|
| A short name (`us`, `eu`) and its Datadog site | Names the keys `DD_US_API_KEY`, `DD_US_APP_KEY`... |
| Both keys (hidden while you type) | Writes them to `.env` (git-ignored, readable only by you); refuses to go on if either is missing. **Generates** the local database password |
| — | Checks each source's API key with its site (read-only) |
| The dashboard JSON files (drag them into the terminal) | Copies them to `config-repo/dashboards/<source>/`. If a dashboard has saved views, asks whether to watch them all (each becomes its own view, e.g. "Shop · Payments"). If the file has no id, asks for it (from the URL) so incidents can link to the dashboard |
| — | After the build, asks every panel once (read-only) and prints how many lines each returned, with the exact filters used |
| "Add another source?" | Say `y` for a second region, for example `eu` on `datadoghq.eu` |
| — | Creates `config-repo/` from `config-template/`, its own Git repository with your settings |
| — | Builds the six services (first time: a few minutes) |

Unattended (for example on a server): set `SOURCES="us eu"` and, per source, `DD_US_API_KEY`, `DD_US_APP_KEY`,
`DD_US_SITE`, `DASHBOARD_FILES_US="shop.json platform.json"` (paths without spaces) and optionally `PRESETS_US=all|none`
(and the same with `EU`); `TIME_ZONE` is optional (default `UTC`). `--no-check` skips the Datadog calls, `--no-build`
skips the build.

## Run

```bash
scripts/system.sh start     # Kafka + database (Docker), then the six services
scripts/system.sh status    # health of each
scripts/system.sh stop      # stops everything; data is kept in Docker volumes
```

Then open **http://localhost:8104**. The page fills as soon as the Detector has 10 minutes of history for a line
(the Collector fetches the last 10 minutes at start, so this is usually immediate).

| Where | What you see |
|---|---|
| http://localhost:8104 | **Live dashboards**, one page per region (US, EU): each watched dashboard collapsed or expanded (remembered), its panels in Datadog's group order as **live line charts** (15m and 1h move every 10 seconds; 4h, 1d, 7d per minute), anomaly times shaded, a red edge on a panel with an open anomaly, badges "● 2 open · ● 1 needs a reason" on each dashboard. Click a panel for its own page (all lines, a sortable legend with newest values); click a line for its **metric page**: graph and details on top, its anomalies of the kept days below, each with **its own reason** (or **Same reason for all** lines of that anomaly). Business-as-usual (BAU) lines — unrecognised (`general`) metrics in any direction, errors going down — need no reason and close by themselves when calm. The header's counter "● 3 open · 2 need a reason" lists those metrics. 🔔 and 🔊 switch on a desktop notification and a sound for new anomalies. **Settings** manages regions, keys and dashboards (see *Change settings*). Charts exist only for panels near the screen and live values are sent only for those, so 20+ dashboards per region stay light |
| `reports/2026-11-16.md` | The day's report, written shortly after midnight in your time zone |
| `http://localhost:810x/actuator/health` | Each service's state and numbers (lines watched, lag, database size, rules in use...). The Collector shows one block per source: keys present, budget, calls, throttling, open breakers |
| `run/<service>.log` | Logs |

See what metrics are coming in, straight from the database and Kafka (read-only):

```bash
scripts/peek.sh panels                # every dashboard / panel: lines, and when its newest value arrived
scripts/peek.sh panel latency         # newest value of each line on panels whose title contains "latency"
scripts/peek.sh watch                 # every 10 s: values in the last 5 minutes and how fresh they are
scripts/peek.sh kafka                 # messages per topic, and whether the Store and Detector keep up
```

A panel with lines but no recent value means its queries return nothing right now: no traffic, or a dashboard
filter that matches nothing.

Ask for a report of a day yourself: `curl -s -X POST localhost:8105/reports/2026-11-16`.

## Change settings (no restart)

### From the page: the Settings tab

Open **http://localhost:8104/#settings** (this machine only: the page and the settings services answer on
`127.0.0.1`, never on the network).

| You want to | In the Settings tab |
|---|---|
| Watch a region (at most two: US and EU) | **Add a region**: pick US or EU, type its API and application keys, **Check keys and add**. The keys are checked with Datadog (read-only) and written only to `.env`; the region is collected from the next poll, no restart |
| New keys for a region | **Replace keys** (same checks); only that region starts again |
| Stop watching a region | **Remove region**: its entry and dashboard files go in one commit, its keys leave `.env` |
| Watch a dashboard | **+ Add dashboard**: choose the exported JSON. The page shows its panels by group and saved views; tick the views to watch; **Check panels** asks each panel once (read-only); **Add** |
| See a dashboard | **Open** (panels, variables, saved views; change the watched views) or **Download** |
| Change a dashboard | **Edit JSON**: the file as it is, with line numbers; a JSON mistake is shown with its line and column while you type; **Format**, **Undo all**, **Check** (panels and views added or removed), **Save**. Or **Replace** with a newer export |
| Stop watching a dashboard | **Delete** |

Every change is **one commit** in `config-repo` ("settings page: add eu/shop.json"), so `git log` shows it and
`git revert` undoes it; nothing is pushed. A file you edited by hand and did not commit is never overwritten (the page
says "commit or discard your edit first"), and a page that is out of date is refused ("reload the page").

### By hand

Everything is in `config-repo/` (its own Git repository): `regions.yml` (regions and their dashboards, also written
by the page) and one file per service. Edit, then commit:

```bash
cd config-repo
git commit -am "watch one more dashboard"
```

A hook tells the Config Service, which pushes the change to every running service within seconds. A change that
breaks a rule (for example a 48-hour report window) is **refused**, the old value stays, and the service's
`/actuator/settings` page says why.

| You want to | Change |
|---|---|
| Watch another dashboard | Put its exported JSON in `config-repo/dashboards/<source>/`, add `- file: dashboards/<source>/<name>.json` under that source's `dashboards` in `regions.yml`, check, commit |
| Watch another region or account | `regions.yml` → `sources` (add an entry), its two keys in `.env` (the Collector re-reads `.env` on every settings change: no restart) |
| Give a dashboard's `$variable` a value | Edit its `default` in the JSON file, or add `filters: { env: prod }` next to the file (wins over the file) |
| Watch a dashboard's saved views | `presets: all` next to the file (every saved view, each its own view), or `presets: [Payments, Billing]` |
| Fewer Datadog calls | `batch-formulas` (default 40): panels due together share one call |
| Faster alerts, or fewer false drops | `settle-delay` (default 60 s): a step is judged only this long after its end, because Datadog may still be receiving its values. Health shows `lateChanges` per source to tune it |
| Tell the system what an unknown metric is (latency, count, ratio, gauge) | `collector.yml` → `classification` |
| Alert more or less sharply, or on new kinds of metrics | `detector.yml` → `rules` (first match wins; any letter case) |
| Group incidents by another tag (for example `endpoint`) | `detector.yml` → `group-by` |
| Which day is "today" | `application.yml` → `anomaly.shared.time-zone` (default `UTC`) |
| Which time zones every time is shown in | `application.yml` → `anomaly.shared.display-time-zones` (default `[UTC, Asia/Kolkata, America/New_York]`, the first is the main one) |
| Reports every 6 hours instead of daily | `reporter.yml` → `window: 6h` (1, 2, 3, 4, 6, 8, 12 or 24 hours) |

### Onboarding a dashboard: check before you run

```bash
scripts/check-dashboards.sh           # every panel of every view, once, read-only, last 15 minutes
```

```
us · Shop · Payments   filters: team IN (web,mobile), service:payments, env:prod
  ✓ API Metrics / Latencies                         12 lines   avg:svc.api.duration{team IN (web,mobile) AND ...
  ✗ API Metrics / Count                              0 lines   count:svc.api.duration{...}
  11 of 12 panels returned lines
```

It reads `config-repo/` as it is on disk, so you can fix a filter, check again, and commit only when it looks right.
A panel with **0 lines** usually means a filter that matches nothing: a variable whose default is a placeholder such as
`select`, a wrong `env`, or a saved view that no longer fits. With an API key that may read dashboards,
`scripts/export-dashboards.sh <source> <id>...` saves a dashboard's JSON for you.

While running, the Collector's health lists the panels that return no lines per view, with their filters, and turns
`DEGRADED` when a whole view returns nothing. Panels due at the same moment share **one Datadog call** (up to 40
formulas), and a source that would exceed its rate limit polls a little less often instead of being refused; health
says so (`pollEverySeconds`).

### Your own dashboards and the Detector's rules

Any dashboard structure works: groups, split groups, formulas, and every widget type that has **metric** queries
(timeseries, query value, top list, table, heat map, ...). Panels without metric queries (logs, APM spans, events,
SLOs, notes) are skipped and counted on the Collector's health page.

Every metric line is watched. The default rules recognise common words in metric names, panel titles and tags
(`latency`, `count`, `hits`, `requests`, `success rate`, `error rate`, `cpu`, status codes 4xx/5xx); anything else gets
a cautious general rule. After the first hour, look at the Detector's health page:

| Health shows | Means | Do |
|---|---|---|
| `linesOnGeneralRuleByPanel` | Lines no rule recognised | Add a rule in `detector.yml` |
| `linesGroupedByDashboard` | Lines without an `api`, `service`, ... tag | Add their tag to `group-by` |

### Several regions at once

Each source runs in its own **lane**: its own rate budget, its own wait after Datadog says "too many requests", its
own limit on calls in flight and its own breakers. A region that is throttled, down or slow only waits by itself; the
others keep their timing. When two or more sites are watched, incidents and reports name the site (`EU · /v1/invoices:
latency up`), the same API in two regions is two incidents, and links open the right site (`app.datadoghq.eu`).

### Upgrading an existing config-repo

`setup.sh` copies `config-template/` once; later improvements to the template do not reach your `config-repo/`
by themselves. Since 17 Nov 2026: in `detector.yml`, rule `errors`, add `"status=4*", "status=5*"` to `tags` (counts
tagged `status`, not only `status_code`, are errors and alert only upwards). `settle-delay` needs no edit (60 s when
missing). In `application.yml`: `time-zone: UTC` (today is the UTC day) and, optionally, `display-time-zones` (every
time is shown in UTC, India and New York when the line is missing). Since 18 Nov 2026: `bau` in `detector.yml` (optional: when missing, `general` in any
direction and `errors` going down are business as usual — copy the block from `config-template/detector.yml` to
change it). Also since 18 Nov 2026: regions live in `regions.yml`. Nothing to do by hand: the first change made in
the Settings tab moves `sources:` out of `collector.yml` in its own commit, comments kept. The Collector now reads
its keys from `.env` itself (start with the new `scripts/system.sh`). A `collector.yml` in the old single-site form
(`datadog-site` with `dashboards`) is shown read-only in the Settings tab: run `scripts/setup.sh` again to manage
regions there. Since 18 Nov 2026 (later): everything is kept 10 days — in `store.yml` set `raw-safety-limit: 10d`,
`summary-retention: 10d`, `history-max-range: 10d`; in `detector.yml` `incident-retention: 10d`; in `reporter.yml`
`catch-up: 10d` (the Kafka topics are changed by `scripts/system.sh start`). Optional in `collector.yml`:
`backfill-new-views`, `backfill-window` (defaults 2d and 4h); in `notifier.yml` the dashboard `ranges` gain `10d`.

## Storage stays small

Everything is kept **10 days** (the most any setting allows; lab system) and stored compressed:

| Kept | How long | Stored |
|---|---|---|
| Raw 10-second values | 10 days, removed by age (reports remove nothing) | Compressed after 6 hours (TimescaleDB, per line in time order; 4.1× on this machine's small sample) |
| Per-minute summaries | 10 days | Compressed after 3 days |
| Incidents and their reasons | 10 days after closing; open and waiting ones until closed | One row each |
| Kafka messages | 10 days | Compressed by the producers (zstd) |
| Reports | The last 30 files | |

The Store's health shows each table's size and, once chunks are compressed, its size before and after compression
("compressed"); it turns **DEGRADED** above 1 GB, so a stopped clean-up is noticed.

## A new dashboard starts with its last 2 days

When a dashboard (or a saved view) is watched for the first time, at first-time setup or when added in the Settings
tab, the Collector first pulls its **last 2 days at 10-second detail** from Datadog (read-only), 4 hours per call,
oldest first, through the region's own rate limit; then its live polling starts. The Settings tab shows "Pulling the
last 2 days of Shop: 45%". Anomalies inside those 2 days are found and listed on the metric pages as **past · found
in backfill**: closed, no notification, no reason needed; one still going on when the 2 days end continues as live.
A restart or an edit never pulls them again (the Store remembers which views it has). Settings in `collector.yml`:
`backfill-new-views: 2d` (0s = off, at most 10d), `backfill-window: 4h`.

## Without Datadog (replay)

Set `recording.enabled: true` in `collector.yml` for a while to save every Datadog answer under `recordings/`, then
`source: replay` with `replay-dir:` that folder plays it back through the whole system, with no Datadog calls.

## Tests

```bash
cd app
./mvnw test                                   # 280+ tests, no Datadog or Docker needed
PLATFORM_DB_PASSWORD=... ./mvnw test -Dlive.db=true   # also the database tests (platform running)
```

## Troubleshooting

| Symptom | Check |
|---|---|
| A source's health: `panelsWithNoLines`, or "N of N panels return no lines" | The filters of that view match nothing: run `scripts/check-dashboards.sh <source>`, fix the JSON's defaults, `filters:` or `presets:`, commit |
| Settings refused: "is an id; dashboards are now JSON files" | Export the dashboard as JSON and list it as `- file:` (see *Onboarding a dashboard*) |
| Many lines "traffic down" at the same moment | Late data: Datadog was still receiving the newest step. Health shows `lateChanges` / `maxChangeAfterSeconds` per source; raise `settle-delay` above that |
| Health: "values still changed N s after their step" | Raise `settle-delay` in `collector.yml` above N (it must stay below `recent-window`) |
| Health: "polling every N s instead of 15 s" | That account's rate limit is tight for this many panels; fine as long as N stays below `recent-window` (2 min) |
| `setup.sh`: "no widgets; is this an exported dashboard JSON?" | Export again from Datadog (Share → Export dashboard JSON); a copy from a text editor may be cut short |
| Collector health: `keys: missing: application key` for a source | Add `DD_<NAME>_APP_KEY` to `.env`, restart the Collector. The other sources keep running meanwhile |
| Collector health: a source's `budgetPercent` above 90, or `throttled` | That account's rate limit: fewer dashboards on it, or a longer `poll-interval`. Each source has its own limit, so the others are not affected |
| Collector health: open breakers for one source | That site is down or refuses those panels; the other sources keep their timing |
| The page says "reconnecting" | `scripts/system.sh status`; `run/notifier.log` |
| A setting did not change | `curl localhost:810x/actuator/settings`: `lastRejected` says why |
| Store health DEGRADED | Database over 1 GB: is the Reporter running? (`localhost:8105/actuator/health`) |

## Layout

```
app/              six services and two shared modules (contracts: Protobuf messages; service-kit: settings, health)
config-template/  default settings; setup.sh copies it to config-repo/ (yours, git-ignored here)
platform/         Kafka and TimescaleDB (Docker Compose), topics, schemas
scripts/          setup.sh, system.sh
openspec/         specifications: 7 capabilities, 80 requirements, and every change with its design and tasks
docs/             message contracts, platform notes, verification results of every change
tools/            check-queries.py: tests every query of an exported dashboard JSON against Datadog (read-only)
```

## How it was built

Spec-driven with [OpenSpec](https://github.com/Fission-AI/OpenSpec): each of the seven changes went through a
proposal, a specification, a design (with data structures and their costs) and tasks, was verified against live
data or recordings, and is archived in `openspec/changes/archive/`. `docs/verification/` records what was measured
for each, including what was found and fixed.

## Licence

MIT, see `LICENSE`.

The page bundles Apache ECharts 5.5.1 (Apache License 2.0, `app/notifier/src/main/resources/static/vendor/ECHARTS-LICENSE.txt`).
