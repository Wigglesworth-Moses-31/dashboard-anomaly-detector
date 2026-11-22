# Proposal: remove-reporter

## Why

The user (22 Nov 2026): "Remove reporter service, we don't need them anymore." The live page (Worst anomalies, the
Anomalies page, reasons) has replaced the daily Markdown report, and the Store already removes old raw values by itself.

## What the Reporter does today, and what happens to each part

| Today | After |
|---|---|
| Writes a Markdown report per window into `reports/` (by default one per day) | Gone. The Anomalies page and Worst anomalies show the same anomalies live. Reports already written stay in `reports/` (never in Git) |
| Catches up missed windows after a stop (`catch-up: 1d`) | Gone with the reports |
| After each report, asks the Store to remove raw points older than the report window | Not needed: the Store's own limit (`raw-safety-limit: 1d`) already removes raw points older than a day, and the per-minute summaries keep their own retention |
| Port 8105, health page, its `reporter.yml` | Gone |

## What Changes

| What | How |
|---|---|
| Code | `app/reporter` deleted and removed from `app/pom.xml` |
| Start and stop | `scripts/system.sh` no longer starts or stops a Reporter (5 services remain) |
| Settings | `config-template/reporter.yml` deleted. On a machine with an existing `config-repo`, its `reporter.yml` is simply no longer read (delete it there if you like) |
| Store calls | `RemoveRawBefore` and `GetDaySummary` stay in the Store's contract, unused for now: removing them would break the contract's compatibility rule for no gain. Their comments say they are unused |
| Database | The Reporter's state table in the `incidents` schema is left as it is (no data wipe); nothing reads it |
| Specs | The `incident-reporting` capability (11 requirements) is removed; Store and settings specs stop naming the Reporter |
| Docs | README (architecture, ports, settings, upgrading), `config-template/README.md`, `scripts/peek.sh` comment |

## Capabilities

### Removed Capabilities
- `incident-reporting`: the daily report and its catch-up.

### Modified Capabilities
- `metric-storage`: raw points are removed by the Store's own limit; the clean-up call stays but nothing calls it.
- `runtime-configuration`: no Reporter settings.

## Impact

`app/reporter`, `app/pom.xml`, `scripts/system.sh`, `config-template/`, README and docs. No contract change, no data
wipe. Five services remain: Config Service, Collector, Store, Detector, Notifier.
