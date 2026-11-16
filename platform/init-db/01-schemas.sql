-- Runs once when the database is first created (design D6).
-- Two schemas: metrics (numbers, later a hypertable split per day) and incidents (ordinary tables).
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE SCHEMA IF NOT EXISTS metrics;
CREATE SCHEMA IF NOT EXISTS incidents;
COMMENT ON SCHEMA metrics   IS 'Metric points; tables are created by the Store change';
COMMENT ON SCHEMA incidents IS 'Incidents and report state; tables are created by the Detector and Reporter changes';
