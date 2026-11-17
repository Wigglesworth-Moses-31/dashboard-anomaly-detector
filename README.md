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
| Notifier | Live page of the day's incidents, updated over RSocket (WebSocket 7104) | **8104** |
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
| Your time zone (it suggests your machine's) | Defines "today" for the live page and the daily report |
| — | Creates `config-repo/` from `config-template/`, its own Git repository with your settings |
| — | Builds the six services (first time: a few minutes) |

Unattended (for example on a server): set `SOURCES="us eu"` and, per source, `DD_US_API_KEY`, `DD_US_APP_KEY`,
`DD_US_SITE`, `DASHBOARDS_US="id1 id2"` (and the same with `EU`), plus `TIME_ZONE`. The older single-source form
(`DD_API_KEY`, `DD_APP_KEY`, `DD_SITE`, `DASHBOARDS`) still works. `--no-check` skips the Datadog calls, `--no-build`
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
| http://localhost:8104 | Live incidents of the day: open ones first, a meter of how far each line is from normal, a link to the dashboard at that moment, counts per group, a filter (`/`). 🔔 and 🔊 switch on a desktop notification and a sound for new incidents |
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

Everything is in `config-repo/` (its own Git repository). Edit, then commit:

```bash
cd config-repo
git commit -am "watch one more dashboard"
```

A hook tells the Config Service, which pushes the change to every running service within seconds. A change that
breaks a rule (for example a 48-hour report window) is **refused**, the old value stays, and the service's
`/actuator/settings` page says why.

| You want to | Change |
|---|---|
| Watch another dashboard | Put its exported JSON in `config-repo/dashboards/<source>/`, add `- file: dashboards/<source>/<name>.json` under that source's `dashboards`, check, commit |
| Watch another region or account | `collector.yml` → `sources` (add an entry), its two keys in `.env`, then restart the Collector once (`scripts/system.sh stop; scripts/system.sh start`) so it can read the new keys |
| Give a dashboard's `$variable` a value | Edit its `default` in the JSON file, or add `filters: { env: prod }` next to the file (wins over the file) |
| Watch a dashboard's saved views | `presets: all` next to the file (every saved view, each its own view), or `presets: [Payments, Billing]` |
| Fewer Datadog calls | `batch-formulas` (default 40): panels due together share one call |
| Tell the system what an unknown metric is (latency, count, ratio, gauge) | `collector.yml` → `classification` |
| Alert more or less sharply, or on new kinds of metrics | `detector.yml` → `rules` (first match wins; any letter case) |
| Group incidents by another tag (for example `endpoint`) | `detector.yml` → `group-by` |
| Another time zone | `application.yml` → `anomaly.shared.time-zone` |
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

## Storage stays small

| Kept | How long |
|---|---|
| Raw 10-second values | Removed after each report (the last 3 hours always stay); never more than 2 days |
| Per-minute summaries, incidents | 2 days (at most 7) |
| Kafka messages | 2 days |
| Reports | The last 30 files |

The Store's health turns **DEGRADED** above 1 GB, so a stopped clean-up is noticed. About 160 bytes per raw value:
226 lines take about 340 MB a day.

## Without Datadog (replay)

Set `recording.enabled: true` in `collector.yml` for a while to save every Datadog answer under `recordings/`, then
`source: replay` with `replay-dir:` that folder plays it back through the whole system, with no Datadog calls.

## Tests

```bash
cd app
./mvnw test                                   # 170+ tests, no Datadog or Docker needed
PLATFORM_DB_PASSWORD=... ./mvnw test -Dlive.db=true   # also the database tests (platform running)
```

## Troubleshooting

| Symptom | Check |
|---|---|
| A source's health: `panelsWithNoLines`, or "N of N panels return no lines" | The filters of that view match nothing: run `scripts/check-dashboards.sh <source>`, fix the JSON's defaults, `filters:` or `presets:`, commit |
| Settings refused: "is an id; dashboards are now JSON files" | Export the dashboard as JSON and list it as `- file:` (see *Onboarding a dashboard*) |
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
