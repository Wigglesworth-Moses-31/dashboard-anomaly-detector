#!/usr/bin/env bash
# Start, stop or check the whole system: platform (Kafka, TimescaleDB) + Config Service + 5 services.
#   scripts/system.sh start | stop | status
# Needs Docker (Docker Desktop, Colima, ...) and Java 25. First time: scripts/setup.sh.
# Logs and process ids go to run/ (git-ignored). Datadog keys from .env reach the Collector only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="$ROOT/run"
SERVICES=(collector:8101 store:8102 detector:8103 notifier:8104 reporter:8105)
COMPOSE=(docker compose --env-file "$ROOT/.env" -p anomaly-platform -f "$ROOT/platform/docker-compose.platform.yml")

wait_for() {  # url seconds
  for _ in $(seq 1 "$2"); do curl -fs "$1" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}

health() {  # port -> status text
  curl -fs "localhost:$1/actuator/health" 2>/dev/null | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["status"])' 2>/dev/null || echo "down"
}

start_service() {  # name port [with-datadog-keys]
  local name="$1" port="$2" jar="$ROOT/app/$1/target/$1-0.1.0-SNAPSHOT.jar"
  if [ -f "$RUN/$name.pid" ] && kill -0 "$(cat "$RUN/$name.pid")" 2>/dev/null; then
    echo "  $name already running"; return
  fi
  if [ "${3:-}" = "with-db-password" ]; then
    # Only the database password from .env, into this one process; never echoed
    ( export PLATFORM_DB_PASSWORD="$(grep -E '^PLATFORM_DB_PASSWORD=' "$ROOT/.env" | cut -d= -f2-)"
      exec java -Xmx256m -jar "$jar" ) > "$RUN/$name.log" 2>&1 &
  elif [ "${3:-}" = "with-datadog-keys" ]; then
    # Read only the two Datadog keys from .env into this one process; never echo them
    ( export DD_API_KEY="$(grep -E '^DD_API_KEY=' "$ROOT/.env" | cut -d= -f2-)" \
             DD_APP_KEY="$(grep -E '^DD_APP_KEY=' "$ROOT/.env" | cut -d= -f2-)"
      exec java -Xmx256m -jar "$jar" ) > "$RUN/$name.log" 2>&1 &
  else
    java -Xmx256m -jar "$jar" > "$RUN/$name.log" 2>&1 &
  fi
  echo $! > "$RUN/$name.pid"
  if wait_for "localhost:$port/actuator/health" 90; then
    echo "  $name  :$port  $(health "$port")"
  else
    echo "  $name  :$port  did not become healthy; see run/$name.log" >&2; return 1
  fi
}

check_prerequisites() {
  [ -f "$ROOT/.env" ] || { echo "No .env yet: run scripts/setup.sh first." >&2; exit 1; }
  [ -d "$ROOT/config-repo/.git" ] || { echo "No config-repo yet: run scripts/setup.sh first." >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker is not running (start Docker Desktop, Colima or your Docker engine)." >&2; exit 1; }
  # An optional local file may point JAVA_HOME at Java 25 (for example a Homebrew install); it is git-ignored.
  # shellcheck disable=SC1091
  [ -f "$ROOT/env.sh" ] && source "$ROOT/env.sh" >/dev/null
  local v
  v="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  [ "${v:-0}" -ge 25 ] 2>/dev/null || { echo "Java 25 or newer is needed (java -version says: ${v:-none})." >&2; exit 1; }
}

start() {
  check_prerequisites
  mkdir -p "$RUN"
  if ! ls "$ROOT"/app/*/target/*-0.1.0-SNAPSHOT.jar >/dev/null 2>&1 || [ "${BUILD:-}" = 1 ]; then
    echo "Building (first time: a few minutes)..."; (cd "$ROOT/app" && ./mvnw -q -B -DskipTests package)
  fi
  echo "Platform:"
  "${COMPOSE[@]}" up -d 2>&1 | grep -E "Started|Running" | sed 's/^/  /' || true
  "$ROOT/platform/create-topics.sh" 2>/dev/null | sed 's/^/  topic: /'
  "$ROOT/platform/install-config-hook.sh" >/dev/null
  echo "Config Service:"
  export CONFIG_REPO_URI
  CONFIG_REPO_URI="$(python3 -I -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve().as_uri())' "$ROOT/config-repo")"
  start_service config-service 8888
  echo "Services:"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"; port="${entry##*:}"
    case "$name" in
      collector) start_service "$name" "$port" with-datadog-keys ;;
      store|detector) start_service "$name" "$port" with-db-password ;;
      *)         start_service "$name" "$port" ;;
    esac
  done
}

stop() {
  echo "Services:"
  for name in reporter notifier detector store collector config-service; do
    if [ -f "$RUN/$name.pid" ]; then
      pid="$(cat "$RUN/$name.pid")"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"                                            # polite stop (SIGTERM)
        for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid"           # only if it did not stop in 20 s
        echo "  $name stopped"
      fi
      rm -f "$RUN/$name.pid"
    fi
  done
  echo "Platform:"
  "${COMPOSE[@]}" down 2>&1 | grep -E "Removed" | sed 's/^/  /' || true
}

status() {
  echo "Platform:"
  docker ps --filter name=anomaly- --format '  {{.Names}}  {{.Status}}' 2>/dev/null || echo "  docker not reachable"
  echo "Services:"
  printf '  config-service  :8888  %s\n' "$(health 8888)"
  for entry in "${SERVICES[@]}"; do printf '  %-14s  :%s  %s\n' "${entry%%:*}" "${entry##*:}" "$(health "${entry##*:}")"; done
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: $0 start | stop | status" >&2; exit 2 ;;
esac
