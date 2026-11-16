#!/usr/bin/env bash
# One-time setup on a new machine: .env (keys), config-repo (settings), build.
#   scripts/setup.sh                 asks for what it needs
#   scripts/setup.sh --no-check      skips the read-only Datadog checks
#   scripts/setup.sh --no-build      skips the build
# Unattended: export DD_API_KEY, DD_APP_KEY, DD_SITE, DASHBOARDS="id1 id2" and TIME_ZONE first.
# Keys are never printed, never passed on a command line, and stay in .env (git-ignored, readable by you only).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK=1
BUILD=1
for arg in "$@"; do
  case "$arg" in
    --no-check) CHECK=0 ;;
    --no-build) BUILD=0 ;;
    *) echo "usage: $0 [--no-check] [--no-build]" >&2; exit 2 ;;
  esac
done

say() { printf '\n== %s\n' "$1"; }
fail() { printf 'setup: %s\n' "$1" >&2; exit 1; }

# ---- 1. what this machine needs ------------------------------------------------------------------------
say "Checking this machine"
for tool in docker git curl python3; do
  command -v "$tool" >/dev/null 2>&1 || fail "$tool is not installed"
done
docker info >/dev/null 2>&1 || fail "Docker is not running (start Docker Desktop, Colima or your Docker engine)"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is needed (docker compose ...)"
# shellcheck disable=SC1091
[ -f "$ROOT/env.sh" ] && source "$ROOT/env.sh" >/dev/null
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/' || true)"
[ "${JAVA_MAJOR:-0}" -ge 25 ] 2>/dev/null || fail "Java 25 or newer is needed (found: ${JAVA_MAJOR:-none}). See README, 'Prerequisites'."
echo "Docker, Java $JAVA_MAJOR, git, curl, python3: ok"

# ---- 2. .env: Datadog keys and a generated database password ------------------------------------------
value() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | cut -d= -f2- || true; }
if [ -f "$ROOT/.env" ] && [ -n "$(value DD_API_KEY)" ] && [ -n "$(value DD_APP_KEY)" ]; then
  say ".env already has your keys (kept as is)"
else
  say "Datadog keys (input is hidden)"
  api="${DD_API_KEY:-}"; app="${DD_APP_KEY:-}"; site="${DD_SITE:-}"
  [ -n "$api" ] || { read -r -s -p "DD_API_KEY: " api; echo; }
  [ -n "$app" ] || { read -r -s -p "DD_APP_KEY: " app; echo; }
  [ -n "$site" ] || { read -r -p "DD_SITE [datadoghq.com]: " site; site="${site:-datadoghq.com}"; }
  [ -n "$api" ] && [ -n "$app" ] || fail "both keys are needed"
  db="$(python3 -I -c 'import secrets; print(secrets.token_hex(24))')"
  umask 077
  printf 'DD_API_KEY=%s\nDD_APP_KEY=%s\nDD_SITE=%s\nPLATFORM_DB_PASSWORD=%s\n' "$api" "$app" "$site" "$db" > "$ROOT/.env"
  chmod 600 "$ROOT/.env"
  unset api app db
  echo "wrote .env (readable by you only); the database password was generated"
fi
SITE="$(value DD_SITE)"; SITE="${SITE:-datadoghq.com}"
[ -n "$(value PLATFORM_DB_PASSWORD)" ] || fail ".env has no PLATFORM_DB_PASSWORD (delete .env and run setup again)"

# Headers go through a private temporary file, never on a command line where other users could see them.
HEADERS="$(mktemp)"
trap 'rm -f "$HEADERS"' EXIT
chmod 600 "$HEADERS"
printf 'DD-API-KEY: %s\nDD-APPLICATION-KEY: %s\n' "$(value DD_API_KEY)" "$(value DD_APP_KEY)" > "$HEADERS"
api_get() { curl -fsS -m 20 -H @"$HEADERS" "https://api.$SITE$1"; }

if [ "$CHECK" = 1 ]; then
  say "Checking the keys with Datadog (read-only)"
  api_get /api/v1/validate | grep -q '"valid":true' || fail "Datadog did not accept DD_API_KEY for site $SITE"
  echo "API key: valid for $SITE"
fi

# ---- 3. settings: config-repo from config-template ----------------------------------------------------
if [ -d "$ROOT/config-repo/.git" ]; then
  say "config-repo already exists (kept as is; change settings there with a commit)"
else
  say "Dashboards to watch"
  dashboards="${DASHBOARDS:-}"
  if [ -z "$dashboards" ]; then
    echo "Dashboard ids are in the URL: https://app.$SITE/dashboard/<id>/..."
    read -r -p "Dashboard ids (separated by spaces or commas): " dashboards
  fi
  dashboards="$(echo "$dashboards" | tr ',' ' ' | xargs)"
  [ -n "$dashboards" ] || fail "at least one dashboard id is needed"
  lines=""
  for id in $dashboards; do
    title="(not checked)"
    if [ "$CHECK" = 1 ]; then
      json="$(api_get "/api/v1/dashboard/$id")" || fail "cannot read dashboard $id (check the id, and that DD_APP_KEY may read dashboards)"
      title="$(printf '%s' "$json" | python3 -I -c 'import json,sys; d=json.load(sys.stdin); w=d.get("widgets",[]); print(d.get("title","?"))')"
      echo "  $id: $title"
    fi
    lines="$lines      - $id                           # $title\n"
  done

  zone="${TIME_ZONE:-}"
  if [ -z "$zone" ]; then
    guess="$(python3 -I -c 'import os; p=os.path.realpath("/etc/localtime"); print(p.split("zoneinfo/")[1] if "zoneinfo/" in p else "UTC")' 2>/dev/null || echo UTC)"
    read -r -p "Time zone for 'today' on the page and in reports [$guess]: " zone
    zone="${zone:-$guess}"
  fi
  python3 -I -c 'import sys, zoneinfo; zoneinfo.ZoneInfo(sys.argv[1])' "$zone" 2>/dev/null || fail "unknown time zone: $zone (use names like Asia/Kolkata, UTC, America/New_York)"

  cp -R "$ROOT/config-template" "$ROOT/config-repo"
  python3 -I - "$ROOT/config-repo" "$SITE" "$zone" "$lines" <<'PY'
import pathlib, sys
repo, site, zone, lines = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4].replace("\\n", "\n").rstrip("\n")
for name, old, new in [("collector.yml", "__DD_SITE__", site), ("collector.yml", "__DASHBOARDS__", lines),
                       ("application.yml", "__TIME_ZONE__", zone)]:
    f = repo / name
    f.write_text(f.read_text().replace(old, new))
PY
  git -C "$ROOT/config-repo" init -q -b main
  git -C "$ROOT/config-repo" add -A
  git -C "$ROOT/config-repo" -c user.name="setup" -c user.email="setup@localhost" commit -q -m "Initial settings: $(echo "$dashboards" | wc -w | xargs) dashboard(s), time zone $zone"
  echo "config-repo created: $(echo "$dashboards" | wc -w | xargs) dashboard(s), Datadog site $SITE, time zone $zone"
fi

# ---- 4. build -----------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  say "Building the six services (first time: a few minutes)"
  (cd "$ROOT/app" && ./mvnw -q -B -DskipTests package)
  echo "built"
fi

say "Done"
cat <<'EOF'
Start everything:   scripts/system.sh start
Live page:          http://localhost:8104
Check health:       scripts/system.sh status
Stop:               scripts/system.sh stop
EOF
