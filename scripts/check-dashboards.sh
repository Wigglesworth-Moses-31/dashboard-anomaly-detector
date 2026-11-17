#!/usr/bin/env bash
# Asks every panel of every watched dashboard once (read-only, last 15 minutes) and prints, per view and panel, how
# many lines came back and the exact question asked. Uses the Collector's own code (add-dashboard-json design J6).
#   scripts/check-dashboards.sh            all sources
#   scripts/check-dashboards.sh us         one source
#   scripts/check-dashboards.sh --at 2026-11-15T08:10:00Z    the 15 minutes before that time (e.g. an incident)
# Reads config-repo/ as it is on disk (you can check before committing). Exit code 1 when a view returns no lines.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$ROOT/env.sh" ] && source "$ROOT/env.sh" >/dev/null
[ -d "$ROOT/config-repo" ] || { echo "No config-repo yet: run scripts/setup.sh first." >&2; exit 2; }
[ -f "$ROOT/.env" ] || { echo "No .env yet: run scripts/setup.sh first." >&2; exit 2; }
JAR="$ROOT/app/collector/target/collector-0.1.0-SNAPSHOT.jar"
[ -f "$JAR" ] || (cd "$ROOT/app" && ./mvnw -q -B -DskipTests package)
# Only Datadog keys from .env into this one process, with shell builtins (never on a command line, never echoed)
( while IFS= read -r line || [ -n "$line" ]; do
    key="${line%%=*}"
    if [[ "$key" =~ ^DD_([A-Z0-9]+_)?(API|APP)_KEY$ ]]; then export "$key=${line#*=}"; fi
  done < "$ROOT/.env"
  unset line key
  exec java -Dloader.main=lab.anomaly.collector.check.CheckDashboards -cp "$JAR" \
       org.springframework.boot.loader.launch.PropertiesLauncher "$ROOT/config-repo" "$@" )
