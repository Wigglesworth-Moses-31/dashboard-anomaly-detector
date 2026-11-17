# Design: add-multi-site

## Context

See proposal.md for the why. Facts from the code that shape the design:

- **One of everything today.** `CollectorEngine.configure` builds one `DatadogSource` (one site, one key pair), one
  `Fetcher` (one token bucket, one 429 gate, one bulkhead), one `Classifier` (one metadata cache) and one
  `Discovery`. Any settings change rebuilds all of them, which also throws away the token bucket's state.
- **The scheduler is already right for many sources.** One platform thread takes due units from a min-heap
  (`Scheduler`, O(log n)), hands each to a virtual thread, and skips a unit whose last poll is still running. All
  waiting (429 gate, tokens, bulkhead) happens *inside* the poll's virtual thread, never on the scheduler thread.
- **A hidden shared bottleneck.** `rediscover` (reading every dashboard definition) runs **on the scheduler
  thread**. Today a slow Datadog only delays its own polls; with two sites, a slow or unreachable EU would freeze
  US polling for as long as EU dashboard reads take (timeouts × retries × dashboards).
- **Line ids already separate sites.** `SeriesIds` = SHA-256 over system, *instance* (the site), query id, tags.
  `PollUnit.key` is the canonical question text, which does **not** include the site: two sites asking the same
  question would share a unit today.
- **Incident groups do not include the site.** `IncidentBook.groupOf` keys groups as `api=/v1/invoices` or
  `dashboard=<id>`, so the same API in two regions would merge into one incident.
- **The EU link is wrong.** `LineFacts.dashboardUrl` turns `datadoghq.eu` into `https://datadoghq.eu/...`; the
  right host is `app.datadoghq.eu`.

## Goals / Non-Goals

**Goals:** every source polled at the same time; nothing one source does (throttling, outage, slowness, a long
dashboard read, a settings change) can delay another; memory that grows with panels, not with sources; old
settings, old line ids and old recordings keep working.

**Non-Goals:** one system per region (data residency), two accounts on the same site, sources other than Datadog,
the Dashboards tab (add-dashboard-view).

## Decisions

### M1. A lane per source (Bulkhead pattern, at the level of a whole source)

```
                        ┌──────────── Lane "us" (datadoghq.com) ────────────┐
                        │ Credentials · DatadogSource · Fetcher             │
 settings ──► Lanes ───►│   (TokenBucket · RateGate · Semaphore bulkhead)   │──┐
 (map by name)          │ Classifier (own metadata LRU) · Discovery (async) │  │
                        │ breakers per unit · budget                        │  │   shared, thread-safe:
                        └───────────────────────────────────────────────────┘  ├─► AnswerTranslator per lane
                        ┌──────────── Lane "eu" (datadoghq.eu) ─────────────┐  │   → one Emitter (by line id)
                        │ (the same pieces, its own instances)              │──┘   → one KafkaPublisher
                        └───────────────────────────────────────────────────┘
 one Scheduler (min-heap of all units) ──► due unit ──► its lane's virtual thread
```

| Piece | Per lane or shared | Why |
|---|---|---|
| Keys, `DatadogSource` (site) | Per lane | Each account has its own address and keys |
| Token bucket, 429 gate | Per lane | Datadog limits **each account** separately; a 429 in the EU says nothing about the US |
| Bulkhead (fair `Semaphore`, `max-concurrent-requests`) | Per lane | A slow EU fills only EU's slots; US calls never wait behind them |
| Circuit breakers | Per unit (units belong to one lane) | Already per unit; unit keys now include the lane (M3) |
| Metadata cache (`ExpiringLruCache`) | Per lane | A metric's type and unit belong to an account |
| `Discovery` | Per lane, **off the scheduler thread** (M4) | Removes the hidden shared bottleneck |
| `Scheduler` | Shared | One heap of all units is O(log n) per poll and already spreads starts by hash; it never blocks (M2) |
| `Emitter` | Shared | It remembers sent steps **by line id**, and ids already differ per site; one copy, no duplication |
| `KafkaPublisher` | Shared | One producer is thread-safe and batches messages from both lanes into fewer network calls |

`Lane` is a small immutable holder plus counters. `Lanes` is a `ConcurrentHashMap<String, Lane>` by source name:
O(1) to find a unit's lane on every poll.

### M2. Why one scheduler cannot become the bottleneck

| What could block | Where it happens | Effect on other lanes |
|---|---|---|
| Waiting for a token, the 429 gate, a bulkhead slot | Inside the poll's **virtual thread** | None: a waiting virtual thread costs about 1 KB and no platform thread |
| A slow answer | Inside the poll's virtual thread | None |
| A unit polled again while its last poll still runs | `Scheduler.takeDue` skips the turn (already built) | None, and waiting work stays bounded: **at most one poll per unit** in flight or waiting |
| Reading dashboard definitions | Today: the scheduler thread. **Now: each lane's own virtual thread** (M4) | None |

The scheduler thread only pops due entries (O(log n)) and submits them. With 2 lanes × 300 units every 15 s it
does about 40 pops per second: microseconds of work.

### M3. Units carry their lane; scheduling ids include it

`PollUnit` gains `lane` (the source name) and a scheduling id `id() = <lane>|<key>`. The scheduler, breakers,
`polledOnce` and the engine's maps all use the id, so the same question in two sources is two units, each polled
from its own site; panels asking the same question **inside one source** still share one unit (O(1) by hash map).

`key` itself stays the canonical question, unchanged: found while building, `AnswerTranslator` derives every
line's query id from `key`, so putting the lane into `key` would have changed every line id. The site already
separates lines of different sources inside the id.

### M4. Discovery off the scheduler thread, one lane at a time per lane

Each lane re-reads its dashboards on its own virtual thread when due (`dashboard-refresh`, default 60 min) or when
its settings change. When done, it hands its new plan (`List<PollUnit>`) to the engine, which merges it on the
scheduler thread in one step:

```
lane eu: discover (slow, maybe minutes) ──► plan ready ──► queue ──► scheduler thread: replace eu's units only
lane us: discover (fast)               ──► plan ready ──► queue ──► scheduler thread: replace us's units only
```

Replacing one lane's units touches only keys with that lane's prefix (kept in a per-lane key set), so US timing is
never reset by an EU re-read. Cost: O(u log n) for the u units of that lane. A lane whose read fails keeps its last
good dashboards (as today, per dashboard).

### M5. Settings: a list of sources, old form still accepted

```yaml
anomaly:
  collector:
    sources:
      - name: us
        site: datadoghq.com
        api-key-env: DD_US_API_KEY
        app-key-env: DD_US_APP_KEY
        dashboards: [abc-def-ghi, jkl-mno-pqr]
        dashboard-filters: {}
        max-concurrent-requests: 8          # optional; else the shared value
        rate-limit-share: 0.8               # optional; else the shared value
      - name: eu
        site: datadoghq.eu
        api-key-env: DD_EU_API_KEY
        app-key-env: DD_EU_APP_KEY
        dashboards: [stu-vwx-yz1]
    poll-interval: 15s                      # shared by all sources, as are step, backfill, retries, breakers...
```

| Rule (checked before the settings are used) | Why |
|---|---|
| Names unique, lower-case letters and digits, 1–12 characters | Used in unit keys, folder names and health |
| **Sites unique** | Line ids are built from the site, so two accounts on one site would produce the same ids for the same query. Two accounts on one site is a Non-Goal |
| Key names match `^DD_([A-Z0-9]+_)?(API|APP)_KEY$` | Settings can name only Datadog keys, never `PLATFORM_DB_PASSWORD` (spec) |
| At least one dashboard per source; a dashboard id appears once per source | As today, per source |
| No `sources` → one source `default` from `datadog-site`, `dashboards`, `dashboard-filters`, keys `DD_API_KEY` / `DD_APP_KEY` | Old settings keep working; same site → **same line ids** as before |
| Both `sources` and `datadog-site` set → refused | One way to say it, never two that disagree |

Checking for duplicates: one `HashSet` per rule, one pass: O(sources + dashboards).

### M6. Keys: looked up by name, never stored in settings

`DatadogCredentials.fromEnvironment(env, apiName, appName)` reads the two named variables. A missing variable gives
an incomplete credential: the lane is created but marked **keys missing (API key | application key | both)**, sends
no request, and shows that on health; other lanes run. Key values never leave the credentials object (`toString`
says only present / missing, as today).

`system.sh` exports into the Collector only the `.env` lines whose names match the same pattern, so the database
password never reaches the Collector's environment. `setup.sh` asks for sources in a loop (name, site, both keys,
dashboard ids), checks each pair read-only (`/api/v1/validate`, then each dashboard), refuses to finish if either key
is missing, and writes `.env` plus `sources:` in `config-repo`.

Adding a source *with keys already in the environment* is live (M7). Adding keys for a new source needs a Collector
restart, because a process's environment is fixed at start; health says so ("keys missing").

### M7. Settings changes rebuild only the lanes that changed

Today any change rebuilds everything and loses the token bucket's state (so the first seconds after a change can
burst). Now each lane has a `LaneConfig` record (site, key names, dashboards, filters, bulkhead size, share). On a
new settings version the engine diffs old and new by name, like a reconciler:

| Diff | Action |
|---|---|
| Same name, equal `LaneConfig` | Keep the lane as is: bucket, gate, breakers, metadata cache, units |
| Same name, changed dashboards or filters only | Keep the lane; re-run its discovery |
| Same name, changed site, keys, bulkhead or share | Build a new lane; drop the old one's units |
| New name | Build and discover |
| Gone name | Drop its units, breakers and lane |

O(sources) to diff; untouched lanes do not notice the change.

### M8. The budget check (spec: warn before Datadog refuses)

Per lane, after each plan:

```
planned per second = units in lane ÷ poll-interval seconds
allowed per second = (reported limit × rate-limit-share) ÷ reported period    (300 per 10 s until Datadog reports)
budget             = planned ÷ allowed
```

O(1) per lane (the unit count is kept in the lane). Health shows `budget` per lane; above 0.9 the lane is
`WARNING` with "plan uses 120% of eu's allowed rate: fewer dashboards or a longer poll-interval". Retries and 429s
are not planned, which is why the line is 90% and not 100%.

### M9. Detector: site in the group key, labels and links

| Change | How | Cost |
|---|---|---|
| Group key | `site|` + today's key, for example `datadoghq.eu|api=/v1/invoices`. Always, also with one site | Same O(1) hash map |
| Title label | When the Detector has seen lines from 2 or more sites, titles start with the site's short label: `datadoghq.com` → US1, `us3.datadoghq.com` → US3, `us5...` → US5, `datadoghq.eu` → EU, `ap1.datadoghq.com` → AP1, `ap2...` → AP2, `ddog-gov.com` → GOV; anything else: the site itself | A count per site in a small map; O(1) per line |
| Links | Host table: `datadoghq.com` → `app.datadoghq.com`, `datadoghq.eu` → **`app.datadoghq.eu`**, `ddog-gov.com` → `app.ddog-gov.com`; any `<x>.datadoghq.com` stays as is | O(1) |

No contract change: the site already travels in every line description (`Source.instance`). The label comes from
the site, not from the source's name in `collector.yml`, so the Detector, Notifier and Reporter need no Collector
settings.

Open incidents saved before the upgrade have keys without the site; after the upgrade, a line of such an incident
starts a new incident once instead of reopening the old one. Harmless, and only at the switch.

### M10. Recording and replay per source

| Mode | Layout |
|---|---|
| Recording | `recordings/<run>/<source>/...` (one folder per lane, the existing format inside) |
| Replay | Each lane reads its folder; answers of all lanes are played **in recorded time order** by a k-way merge: a min-heap holding the next answer of each lane, O(N log k) for N answers and k lanes |
| Old recordings | A folder without source sub-folders replays as source `default`, as today |

The **EU test without an EU account**: copy a US recording into `<run>/eu/`, with `site` set to `datadoghq.eu` in
its dashboard files. Replayed, it produces a second set of lines with different ids, a second budget, a second
health block and EU links, exactly as a real EU source would.

### M11. Health

`/actuator/health` gets `sources: { us: {...}, eu: {...} }`:

| Per source | Meaning |
|---|---|
| `keys` | present / missing: API key / application key / both |
| `dashboards`, `units`, `panels` | What it watches |
| `budget` | M8 |
| `calls`, `failed`, `throttled`, `inFlight` | Its own Fetcher's counters |
| `gateClosedForMs` | Its 429 wait, if any |
| `openBreakers` | Its failing panels |
| `lastDiscovery`, `lastSuccessAgoMs` | Freshness |

The Collector is `UP` when every lane polls; `DEGRADED` (the services' existing warning status) with a `warnings`
list naming the source when a lane is over budget, missing keys or has open breakers; never `DOWN` because of one
lane.

### M12. Data structures, algorithms and patterns

| Need | Choice | Cost |
|---|---|---|
| Next poll due, all sources | Min-heap (`PriorityQueue`) with lazy deletion of stale entries (existing) | O(log n) per poll |
| Spread starts across sources | MurmurHash3 finaliser of the unit key (existing); keys now include the lane, so US and EU units land apart | O(1) |
| Find a unit's lane | Hash map by source name | O(1) |
| Same question on several panels of one source | Hash map by `<lane>|question` (existing) | O(1) |
| Rate limit per account | Token bucket per lane (existing class, now one per lane) | O(1) |
| 429 wait per account | Gate per lane (existing class) | O(1) |
| Calls in flight per account | Fair semaphore per lane (Bulkhead) | O(1) |
| Failing panels | Circuit breaker per unit (existing) | O(1) |
| Metric metadata per account | LRU with expiry per lane (existing class) | O(1) |
| Settings changes | Reconciler: diff old and new lanes by name | O(sources) |
| Replay of several sources | k-way merge with a min-heap | O(N log k) |
| Duplicate names, sites, dashboards | One `HashSet` pass each | O(n) |
| Patterns | Bulkhead (per lane), Circuit Breaker, Token Bucket, Strategy (`MetricSource`: Datadog, recording, replay), Reconciler (M7), Immutable settings records | — |

**Memory:** a lane is about a dozen small objects; units, breakers and line state grow with **panels and lines**,
whichever source they come from. Two sources with 15 dashboards each is about twice one source's memory, the same
as 30 dashboards in one source. **Threads:** still one platform scheduler thread; polls are virtual threads.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| A unit polled at most once in flight means a lane stuck for minutes collects nothing for that time | Its breakers open and health says so; the other lane is unaffected (that is the goal) |
| Group keys change at the upgrade (M9) | At most one extra incident per group at the switch; documented |
| Rebuilding a lane after a key or site change restarts its backfill | Only that lane, and only on such a change |
| Two accounts on one site are refused | A clear message; supporting it would need the account in the line id and a contract change |
| The budget ignores retries | Warn at 90%; the 429 gate and token bucket still protect at runtime |

## Migration Plan

1. Deploy the Collector with `sources` support; existing `collector.yml` (single site) keeps working as `default`
   with the same line ids, so the Store, Detector warm start and incidents carry on.
2. Deploy the Detector with the new links and group keys.
3. When ready for EU: add `DD_EU_API_KEY` / `DD_EU_APP_KEY` to `.env`, restart the Collector (environment), then
   move the settings to `sources:` with `us` and `eu` and commit.
4. Rollback: the old single-site settings are still valid; going back drops only the EU lane.

## Open Questions

None. Confirmed with the user: both keys per source, both regions polled at the same time, one system for both.
