-- add-store design S1. What each metric line is, and its numbers.
CREATE TABLE metrics.series (
  series_id   text PRIMARY KEY,
  metric      text NOT NULL,
  group_tags  jsonb NOT NULL,
  kind        text NOT NULL,
  unit        text NOT NULL,
  query_text  text NOT NULL,
  views       jsonb NOT NULL,
  descriptor  bytea NOT NULL,              -- the full SeriesDescriptor (Protobuf) for anything not in columns
  first_seen  timestamptz NOT NULL,
  updated     timestamptz NOT NULL
);

-- No foreign key to series: points may arrive before their description (spec: numbers before their description).
CREATE TABLE metrics.points (
  series_id text NOT NULL,
  ts        timestamptz NOT NULL,
  value     double precision NOT NULL,
  partial   boolean NOT NULL DEFAULT false,
  PRIMARY KEY (series_id, ts)               -- "stored once"; also serves history questions
);

-- One chunk per day: deleting a day drops a chunk instead of deleting rows.
SELECT create_hypertable('metrics.points', by_range('ts', INTERVAL '1 day'));
