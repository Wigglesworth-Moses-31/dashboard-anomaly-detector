-- add-detector design D7: incidents and the transactional outbox.
CREATE TABLE incidents.incident (
  incident_id text PRIMARY KEY,
  group_key   text NOT NULL,
  state       text NOT NULL,                 -- OPEN or CLOSED
  title       text NOT NULL,
  started     timestamptz NOT NULL,
  ended       timestamptz,
  sequence    bigint NOT NULL,               -- of the last event
  body        bytea NOT NULL,                -- the full Incident (Protobuf)
  updated     timestamptz NOT NULL
);
-- Never two open incidents for one group, even with a bug.
CREATE UNIQUE INDEX one_open_per_group ON incidents.incident (group_key) WHERE state = 'OPEN';
CREATE INDEX incident_closed_ended ON incidents.incident (ended) WHERE state = 'CLOSED';

-- Every incident change, written in the same transaction as the incident; sent to Kafka by the publisher.
CREATE TABLE incidents.outbox (
  id          bigserial PRIMARY KEY,
  event_id    uuid NOT NULL UNIQUE,
  incident_id text NOT NULL,
  payload     bytea NOT NULL,                -- the IncidentEvent (Protobuf)
  arrived_ms  bigint NOT NULL,               -- when the deciding value reached Kafka (time-to-announce)
  created     timestamptz NOT NULL,
  published   timestamptz
);
CREATE INDEX outbox_unpublished ON incidents.outbox (id) WHERE published IS NULL;
