#!/usr/bin/env bash
# Creates the Kafka topics from design D2. Safe to run again: existing topics keep their messages; their retention is
# set to 24 hours in place (keep-one-day).
set -euo pipefail
KEEP_MS=86400000
# Wait until Kafka reports healthy (up to about 2 minutes) instead of failing on a cold start
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' anomaly-kafka 2>/dev/null)" = healthy ] && break
  sleep 4
done
[ "$(docker inspect -f '{{.State.Health.Status}}' anomaly-kafka 2>/dev/null)" = healthy ] || { echo "Kafka is not healthy; is the platform running?" >&2; exit 1; }
create() {  # name partitions extra-configs...
  local name="$1" partitions="$2"; shift 2
  local args=()
  for c in "$@"; do args+=(--config "$c"); done
  docker exec anomaly-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic "$name" --partitions "$partitions" --replication-factor 1 ${args[@]+"${args[@]}"}  # macOS Bash 3.2: safe when empty
}
# What each metric line is: keep only the latest description per line (compacted)
create metric-series   3 cleanup.policy=compact
# The numbers: 6 partitions so several readers can share the work; keyed by series id keeps order per line
create metric-points   6 retention.ms=$KEEP_MS
# Incident opened / updated / closed
create incident-events 3 retention.ms=$KEEP_MS
create incident-commands 1 retention.ms=$KEEP_MS
# Spring Cloud Bus: carries "settings changed, reload" to every service (design D5)
create springCloudBus  1
# Existing topics: set 24 hours in place (keep-one-day)
for topic in metric-points incident-events incident-commands; do
  docker exec anomaly-kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --alter \
    --entity-type topics --entity-name "$topic" --add-config retention.ms=$KEEP_MS >/dev/null
done
docker exec anomaly-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
