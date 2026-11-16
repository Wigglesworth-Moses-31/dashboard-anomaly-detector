# Dashboard Anomaly Detector

Watches your Datadog dashboards every 15 seconds, catches **short spikes** that monitors miss or report late,
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
| A Datadog account and its **site** | `datadoghq.com` (US1), `datadoghq.eu`, `us3.datadoghq.com`, `us5.datadoghq.com`, `ap1.datadoghq.com` |
| A Datadog **API key** | Organization settings → API keys |
| A Datadog **application key** with read scopes | Organization settings → Application keys. Scopes: `dashboards_read`, `timeseries_query`, `metrics_read` |
| The **ids** of the dashboards to watch | From each dashboard's URL: `https://app.datadoghq.com/dashboard/`**`abc-def-ghi`**`/...` |
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

`setup.sh` asks for four things and does the rest:

| It asks | It does |
|---|---|
| `DD_API_KEY`, `DD_APP_KEY` (hidden while you type) and your Datadog site | Writes `.env` (git-ignored, readable only by you) and **generates** the local database password |
| — | Checks the API key with Datadog (read-only) |
| The dashboard ids | Reads each dashboard once (read-only) and shows its title, so a wrong id is caught now |
| Your time zone (it suggests your machine's) | Defines "today" for the live page and the daily report |
| — | Creates `config-repo/` from `config-template/`, its own Git repository with your settings |
| — | Builds the six services (first time: a few minutes) |

Unattended (for example on a server): set `DD_API_KEY`, `DD_APP_KEY`, `DD_SITE`, `DASHBOARDS="id1 id2"` and
`TIME_ZONE` in the environment first. `--no-check` skips the Datadog calls, `--no-build` skips the build.

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
| `http://localhost:810x/actuator/health` | Each service's state and numbers (lines watched, lag, database size, rules in use...) |
| `run/<service>.log` | Logs |

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
| Watch another dashboard | `collector.yml` → `dashboards` (add its id) |
| Give a dashboard's `$variable` a value (otherwise its default is used) | `collector.yml` → `dashboard-filters` |
| Tell the system what an unknown metric is (latency, count, ratio, gauge) | `collector.yml` → `classification` |
| Alert more or less sharply, or on new kinds of metrics | `detector.yml` → `rules` (first match wins; any letter case) |
| Group incidents by another tag (for example `endpoint`) | `detector.yml` → `group-by` |
| Another time zone | `application.yml` → `anomaly.shared.time-zone` |
| Reports every 6 hours instead of daily | `reporter.yml` → `window: 6h` (1, 2, 3, 4, 6, 8, 12 or 24 hours) |

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
| `setup.sh`: "cannot read dashboard" | The id (from the URL), the site, and that the application key has `dashboards_read` |
| Collector health shows `throttled` or open breakers | Datadog's rate limit: fewer dashboards, or a longer `poll-interval` |
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
