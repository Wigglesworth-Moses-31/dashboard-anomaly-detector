-- add-incident-resolution: an incident is OPEN, CALM (all lines normal again, waiting for a reason) or CLOSED.
-- A group has at most one incident that is not closed: the book reopens a calm one instead of opening another.
DROP INDEX IF EXISTS incidents.one_open_per_group;
CREATE UNIQUE INDEX one_waiting_per_group ON incidents.incident (group_key) WHERE state IN ('OPEN', 'CALM');
COMMENT ON COLUMN incidents.incident.state IS 'OPEN, CALM (waiting for a reason) or CLOSED';
