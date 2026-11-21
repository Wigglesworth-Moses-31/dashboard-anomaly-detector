# Proposal: raise-size-alarm

## Why

The user (21 Nov 2026): "we need to update db storage from 1 GB to 10 GB". The Store's size alarm turns its health
DEGRADED above 1 GB; with more dashboards and regions that is too low for this machine.

## What Changes

| What | Was | Now |
|---|---|---|
| `max-database-size` default (`store.yml`) | 1GB | **10GB** |
| Spec "Size alarm" | default 1 GB | default 10 GB |
| README | "DEGRADED above 1 GB" | 10 GB |

It is an alarm, not a cap: the database is kept small by retention (24 hours) and compression, unchanged. The setting
still changes without a restart.

## Capabilities

### Modified Capabilities
- `metric-storage`: the size alarm's default is 10 GB.

## Impact

`config-template/store.yml`, this machine's `config-repo/store.yml` (local commit), README, the spec.
