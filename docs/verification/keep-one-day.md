# Verification: keep-one-day

21 Nov 2026. Everything is kept 24 hours (metrics, anomalies and reasons, Kafka); a new dashboard pulls its last
24 hours.

| Check | Result |
|---|---|
| Tests | All modules pass (see add-datadog-look for the total) |
| Kafka | `metric-points` (and the incident topics) at `retention.ms=86400000` after `scripts/system.sh start` |
| Store | "Storage limits applied: raw points 86400 seconds, per-minute summaries 86400 seconds" (this machine's `config-repo` set to 1 day, local commit) |
| Backfill | `backfill-new-views` defaults to 1 day (6 windows of 4 hours) |

## Found and fixed during the check

| Seen | Why | Fix |
|---|---|---|
| After 3 days off, the page knew none of the 19 waiting incidents (12 open, 7 calm) that the database had | Kafka keeps 24 hours; a restored incident counted as "just announced", and the first re-announce check ran an hour after start | Restored waiting incidents are announced at the Detector's first loop (a test covers it); the page showed all 19 again |
