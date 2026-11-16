# Local platform: Kafka and TimescaleDB

The two tools every service needs, running in Docker (Colima) on this Mac.
Files: `platform/docker-compose.platform.yml`, `platform/init-db/01-schemas.sql`, `platform/create-topics.sh`.

| Tool | Version | Container | Port on the Mac |
|---|---|---|---|
| Kafka (one node, KRaft, no ZooKeeper) | 4.3.2 | `anomaly-kafka` | 59092 |
| TimescaleDB (Postgres 17) | 2.32.0 | `anomaly-timescaledb` | 55432 (database `anomaly`, user `anomaly`) |

The database password is `PLATFORM_DB_PASSWORD` in `.env` (never in these files).

## Start
Colima must be running first (`colima start --cpu 4 --memory 8 --disk 40`). Colima also starts the user's
other containers (ai-engineering-bootcamp, cka); leave them alone.
```bash
cd platform
docker compose --env-file ../.env -p anomaly-platform -f docker-compose.platform.yml up -d
./create-topics.sh
```
`create-topics.sh` is safe to run every time: topics that already exist are left as they are.

## Check
```bash
docker ps --filter name=anomaly- --format '{{.Names}}  {{.Status}}'
```
Both lines should say `(healthy)` within about a minute.

```bash
docker exec anomaly-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```
Expected: `incident-events`, `metric-points`, `metric-series`.

```bash
docker exec anomaly-timescaledb psql -U anomaly -d anomaly -c '\dn'
```
Expected: the schemas `incidents` and `metrics` (plus Postgres' own).

## Stop (data is kept)
```bash
cd platform
docker compose --env-file ../.env -p anomaly-platform -f docker-compose.platform.yml down
```

## Reset (deletes all Kafka messages and database data)
Only when starting over on purpose:
```bash
cd platform
docker compose --env-file ../.env -p anomaly-platform -f docker-compose.platform.yml down -v
```

## Topics (design D2)
| Topic | Partitions | Kept | Why |
|---|---|---|---|
| `metric-series` | 3 | Latest message per key (compacted) | What each metric line is; only the newest description matters |
| `metric-points` | 6 | 2 days | The numbers; 6 partitions let several readers share the work; the series id key keeps each line in order |
| `incident-events` | 3 | 2 days | Incident opened, updated, closed |

Kafka is set to never create topics by itself, so a typo in a topic name fails loudly instead of silently
creating a new, empty topic.

## Memory
Kafka is limited to 512 MB of heap and TimescaleDB tunes itself for 512 MB, to leave room in Colima's 8 GB
for the user's other containers and the lab traffic.

## The Collector: live, recording, replay
All three are settings in `config-repo/collector.yml`; change, commit, and the running Collector applies them
within a second (no restart).

| Mode | Settings | What happens |
|---|---|---|
| Live (default) | `source: datadog`, `recording.enabled: false` | Polls the dashboards in `dashboards`, sends to `metric-series` and `metric-points` |
| Live + recording | `recording.enabled: true` (`recording.dir: recordings`) | Also writes every Datadog answer to `recordings/<date>/*.jsonl` and every sent message to `recordings/published.jsonl` (no keys in either) |
| Replay | `source: replay`, `replay-dir: <folder of a recording>` | No Datadog at all: plays the recording through the same steps and publishes the same messages |

Turn recording on, for example:
```bash
sed -i '' 's/enabled: false  /enabled: true   /' config-repo/collector.yml
git -C config-repo commit -am "record collector answers"
```
Check it: `curl -s localhost:8101/actuator/health` (component `collector`: polls, lines, messages, rate limit,
open breakers).

Analyse a run: `python3 -I tools/analyze_collector_run.py` (counts, delay, classification, spikes caught, and
collected values compared with Datadog read directly).

## The Store: tables and how to look at the data

| Table | What it holds | Kept |
|---|---|---|
| `metrics.series` | One row per metric line: metric, tags, kind, unit, query, the panels it is on | Always (226 rows) |
| `metrics.points` | Every finished 10-second value, once per line and time (hypertable, one chunk per day) | At most `raw-safety-limit` (2 days) |
| `metrics.points_1m` | Per-minute min, max, average and count (continuous aggregate, refreshed every minute) | `summary-retention` (2 days) |

Look at the data (no password needed inside the container):
```bash
docker exec -it anomaly-timescaledb psql -U anomaly -d anomaly
```
```sql
select count(*), min(ts), max(ts) from metrics.points;
select series_id, metric, group_tags from metrics.series where group_tags::text like '%invoices%';
select minute, max, avg from metrics.points_1m where series_id = '341ca619230b22a2' order by minute desc limit 10;
select hypertable_name, config from timescaledb_information.jobs where proc_name like 'policy%';
select pg_size_pretty(pg_database_size('anomaly'));
```

The Store's own view: `curl -s localhost:8102/actuator/health` (Kafka lag, last save, rows written, save delay,
database size and largest tables; DEGRADED above `max-database-size`, 1 GB).

Size at lab scale (measured 15 Nov): about 166 bytes per raw point, so about 340 MB a day and about 680 MB at the
2-day limit. Limits above 7 days are rejected.

## The Detector: incidents and rules

| Table | What it holds | Kept |
|---|---|---|
| `incidents.incident` | One row per incident: group, state (OPEN/CLOSED), title, start, end, the full incident (Protobuf) | Closed ones `incident-retention` (2 days, at most 7) |
| `incidents.outbox` | Every incident change waiting to be (or already) sent to Kafka `incident-events` | Sent ones 1 day |

Look at incidents:
```bash
docker exec -it anomaly-timescaledb psql -U anomaly -d anomaly
```
```sql
select group_key, state, to_char(started,'HH24:MI:SS') started, to_char(ended,'HH24:MI:SS') ended, title
from incidents.incident order by started desc limit 20;
select count(*) filter (where published is null) waiting, count(*) sent from incidents.outbox;
```

The Detector's own view: `curl -s localhost:8103/actuator/health` (lines per rule, lines on the general rule by
panel, warming up, open incidents, dashboards not arriving, read-to-announce time). Its rules are in
`config-repo/detector.yml`: first match wins; a commit changes them without a restart.

## The Notifier: the live page

Open **http://localhost:8104** in a browser. It connects over RSocket (WebSocket, port 7104), shows every incident of
the day (open first, then closed), and updates by itself. `/` jumps to the filter; 🔔 and 🔊 switch on a desktop
notification and a short sound for new incidents (the browser asks once).

| Setting | Where | Default |
|---|---|---|
| Which "today", and the times shown | `config-repo/application.yml` `anomaly.shared.time-zone` (shared with the Reporter) | Asia/Kolkata |
| Count the day by start or by arrival (demos of old recordings) | `config-repo/notifier.yml` `day-by` | started |
| Port for pages | `config-repo/notifier.yml` `rsocket-port` | 7104 |

A commit changes them without a restart; open pages re-sync within seconds. Health: `curl -s localhost:8104/actuator/health`.

## The Reporter: reports and clean-up

After each window ends (a day by default, in the shared time zone), the Reporter writes `reports/<day>.md`, checks it,
then asks the Store to remove that window's raw points (keeping the last 3 hours). Missed windows are caught up on
start (up to 2 days). Ask for a day yourself:
```bash
curl -s -X POST localhost:8105/reports/2026-11-15
```
Settings in `config-repo/reporter.yml`: `window` (1h, 2h, 3h, 4h, 6h, 8h, 12h or 24h), `grace`, `keep-before-end`,
`catch-up` (at most 2d), `output-dir`, `keep-reports`. Health: `curl -s localhost:8105/actuator/health`. The state of
each window is in `reports/.state.json` (pending, written, verified, cleaned).
