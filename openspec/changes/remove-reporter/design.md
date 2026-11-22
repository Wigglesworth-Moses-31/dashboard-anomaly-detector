# Design: remove-reporter

- **Delete, do not disable.** A disabled module still builds and confuses; the code stays in Git history.
- **Keep the Store calls.** `GetDaySummary` and `RemoveRawBefore` stay: the contract rule forbids removing calls, and
  they cost nothing unused. Their comments say no service calls them.
- **No data change.** The Reporter's table in `incidents` stays; `reports/` stays on disk (never in Git).
- **Raw data.** The Store's own retention (`raw-safety-limit: 1d`, compressed after 6 h) already removes raw points;
  the Reporter's clean-up only did it a few hours earlier.
- **Existing machines.** `scripts/system.sh stop` from the new version no longer knows the Reporter, so a Reporter
  started by an older version keeps running: the new `stop` also stops a leftover `run/reporter.pid` once.
