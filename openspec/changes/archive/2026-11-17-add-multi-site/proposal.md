# Proposal: add-multi-site

## Why

The user's Datadog is in **two regions, US and EU** (16 Nov 2026). Each region is a separate Datadog account with its
own address, its own keys, its own dashboards and its own rate limit. Today the Collector watches one site with one
key pair, so half of the dashboards cannot be watched at all.

The user asked for both regions **at the same time, with no bottleneck**: a slow or failing region must never
delay the other, and the code must stay memory- and time-efficient.

Much is already in place: every line's id includes the site (`SeriesIds`: SHA-256 over system, **site**, query,
tags), so a US line and an EU line can never be confused, even for the same query.

## What Changes

| Step | In plain words |
|---|---|
| **A list of sources** | `collector.yml` gets `sources:`, one entry per Datadog account: a short name (`us`, `eu`), its site, the *names* of its two keys, its dashboards and their filters. The old single-site settings keep working as one source |
| **Keys stay out of settings** | `.env` holds `DD_US_API_KEY`, `DD_EU_API_KEY`, ...; settings only *name* them. A key name must look like `DD_<NAME>_API_KEY` / `DD_<NAME>_APP_KEY`, so a setting can never make the Collector read another secret (such as the database password) |
| **One lane per source: no shared bottleneck** | Each source gets its own lane: its own rate budget (Datadog limits each account separately), its own "wait after 429" gate, its own limit on calls in flight (bulkhead), its own breakers and its own metadata cache. A 429, an outage or a slow answer in the EU never takes a slot, a token or a wait from the US |
| **Run together** | Both lanes poll at the same time on virtual threads, from one scheduler (a min-heap of due times, O(log n) per poll). Their start times are spread across the interval (hash jitter), so the two regions never burst together |
| **Shared where sharing is free** | One Kafka producer (it batches messages from both lanes), one translator, one classifier. The lines of both regions flow into the same topics; nothing downstream needs a second copy |
| **A budget check before Datadog says no** | For each source, the Collector computes how much of its rate limit the planned polls will use (calls per second ÷ allowed per second) and shows it on its health page; above 90% it warns, naming the source, before any 429 |
| **Live changes** | Adding or removing a source or a dashboard in `collector.yml` takes effect after the commit, without a restart, if its keys are already in the environment. A source whose keys are missing is shown as "keys missing" and the other sources run |
| **The right link per region** | Incident links open the right site: `app.datadoghq.com`, **`app.datadoghq.eu`**, `us3.datadoghq.com`... (today an EU link would miss the `app.` part) |
| **The source is visible** | Incidents, the live page, the report and the health pages name the source when there is more than one: "EU · Shop / Latency" |
| **Setup** | `setup.sh` asks for one or more sources (name, site, keys, dashboard ids), checks each with read-only calls, and writes `.env` and `config-repo`. `system.sh` passes only the `DD_*_API_KEY` / `DD_*_APP_KEY` values to the Collector |
| **Replay** | A recording keeps each source in its own folder; replay plays all of them together |

## How we will know it works

| Check | Expected |
|---|---|
| Two sources at once | With US and EU configured (the second can be a replay recording), both send lines within one interval of start, and their ids never collide |
| EU outage | The EU source's site unreachable: its panels' breakers open, its health says so; US polls keep their timing (no change in US poll delay) |
| EU 429 | A 429 on EU closes only EU's gate for the reset time; US calls continue at full rate |
| Slow EU | EU answers taking 10 s: EU's in-flight calls stay at its own limit; US in-flight and timing are unchanged |
| Budget warning | Planning 120% of a source's limit shows a warning naming that source, before any 429 |
| Key safety | A setting naming `PLATFORM_DB_PASSWORD` as a key is refused; no key value appears in any log, health page or message |
| Old settings | A `collector.yml` with only `datadog-site` and `dashboards` still runs as one source, with the same line ids as before |
| Links | An EU incident's link opens `https://app.datadoghq.eu/dashboard/...` |
| Cost | Per source: a few small objects (bucket, gate, bulkhead, breakers by panel); memory grows with panels, not with sources. Per poll: O(log n) to schedule, O(1) for the budget, gate and bulkhead |

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `metric-collection`: several sources at once, each isolated (rate budget, 429 gate, bulkhead, breakers, metadata);
  per-source settings and key names; the budget check; per-source health; per-source recording and replay.
- `runtime-configuration`: the key-name rule (settings name secrets, never hold them, and may name only Datadog
  keys).
- `anomaly-detection`: dashboard links for every Datadog site, and the source named in incident titles when there
  is more than one.

## Lab limits

- The personal trial is one US account. The second source is tested with a **replay recording presented as a
  second site** (no second Datadog account is created) and with simulated outages, 429s and slow answers.
- One Collector process watches all sources. One process per region is possible later (each would get a subset of
  `sources`), but not needed: polling is network-bound and light.

## Out of scope

| Not in this change | Why |
|---|---|
| Keeping EU data inside the EU (one full system per region) | A data-residency policy question for the user's organisation, not a technical need today |
| The Dashboards tab | `add-dashboard-view`, next; it will list dashboards per source |
| Database compression | `add-compression`, after this; more sources mean more lines |
| Sources other than Datadog | The interface allows it; not needed now |

## Impact

- `app/collector`: settings (`sources`), key lookup by name, one lane per source, the budget check, per-source
  health, recording and replay per source, tests.
- `app/detector`: link host per site; source in incident titles when there are several.
- `app/notifier`, `app/reporter`: show the source name where a dashboard is named.
- `config-template/collector.yml`, `scripts/setup.sh`, `scripts/system.sh`, `.env.example`, `README.md`.
- No contract change (the site is already in every line), no new topic, no database change, nothing created in
  Datadog.
