-- add-store design S1. Per-minute summaries kept up to date by TimescaleDB (continuous aggregate).
-- materialized_only = false: answers also include the newest minutes not summarised yet.
CREATE MATERIALIZED VIEW metrics.points_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT series_id,
       time_bucket('1 minute', ts) AS minute,
       min(value) AS min,
       max(value) AS max,
       avg(value) AS avg,
       count(*)   AS n
FROM metrics.points
GROUP BY series_id, minute
WITH NO DATA;
