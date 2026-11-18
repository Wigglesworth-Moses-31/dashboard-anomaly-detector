#!/usr/bin/env bash
# One-time setup on a new machine: .env (keys), config-repo (settings), build.
#   scripts/setup.sh                 asks for what it needs: one or more Datadog sources (for example US and EU)
#   scripts/setup.sh --no-check      skips the read-only Datadog checks
#   scripts/setup.sh --no-build      skips the build
# Dashboards are exported JSON files (Datadog: dashboard > Share > Export dashboard JSON).
# Unattended: export SOURCES="us eu" and, per source, DD_<NAME>_API_KEY, DD_<NAME>_APP_KEY, DD_<NAME>_SITE,
# DASHBOARD_FILES_<NAME>="a.json b.json" (paths without spaces), optionally PRESETS_<NAME>=all|none (saved views,
# default all); optionally TIME_ZONE (default UTC). Interactive input accepts dragged paths with spaces.
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
[ "${JAVA_MAJOR:-0}" -ge 25 ] 2>/dev/null || fail "Java 25 or newer is needed (found: ${JAVA_MAJOR:-none}). See README, 'What you need'."
echo "Docker, Java $JAVA_MAJOR, git, curl, python3: ok"

# Reads one value from .env by name (only inside this script; never printed).
value() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }

# Writes NAME=VALUE into .env, replacing an older line of that name. printf is a shell builtin: values never appear
# on a command line. The file stays readable by you only.
put_env() {
  local name="$1" val="$2" tmp
  umask 077
  touch "$ROOT/.env"
  tmp="$(mktemp "$ROOT/.env.XXXXXX")"
  grep -v -E "^$name=" "$ROOT/.env" > "$tmp" || true
  printf '%s=%s\n' "$name" "$val" >> "$tmp"
  mv "$tmp" "$ROOT/.env"
  chmod 600 "$ROOT/.env"
}

# Calls Datadog read-only for one site with that source's keys, through a private headers file (never argv).
api_get() {  # site api-key-name app-key-name path
  local headers out
  headers="$(mktemp)"; chmod 600 "$headers"
  printf 'DD-API-KEY: %s\nDD-APPLICATION-KEY: %s\n' "$(value "$2")" "$(value "$3")" > "$headers"
  out="$(curl -fsS -m 20 -H @"$headers" "https://api.$1$4")" || { rm -f "$headers"; return 1; }
  rm -f "$headers"
  printf '%s' "$out"
}

# ---- 2. the database password ----------------------------------------------------------------------------
if [ -z "$(value PLATFORM_DB_PASSWORD)" ]; then
  put_env PLATFORM_DB_PASSWORD "$(python3 -I -c 'import secrets; print(secrets.token_hex(24))')"
  echo "generated the local database password (in .env)"
fi

# ---- 3. sources: keys, checks, settings ------------------------------------------------------------------
if [ -d "$ROOT/config-repo/.git" ]; then
  say "config-repo already exists (kept as is; change settings there with a commit)"
else
  NAMES=()
  SITES=()
  DASH=()
  if [ -n "${DASHBOARDS:-}" ] || env | grep -qE '^DASHBOARDS_[A-Z0-9]+='; then
    fail "dashboards are JSON files now, not ids: export each in Datadog (Share > Export dashboard JSON) and set DASHBOARD_FILES_<NAME>=\"a.json b.json\""
  fi

  # Facts of an exported dashboard JSON: "ok<TAB>title<TAB>saved views<TAB>id", or the problem.
  inspect_json() {
    python3 -I - "$1" <<'PY2'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("not valid JSON:", str(e).splitlines()[0]); sys.exit(0)
if not isinstance(d, dict) or not d.get("widgets"):
    print("no widgets; is this an exported dashboard JSON?"); sys.exit(0)
print("ok\t%s\t%d\t%s" % (d.get("title", "?"), len(d.get("template_variable_presets") or []), d.get("id") or ""))
PY2
  }

  ENTRIES=()          # per source: the dashboards block for regions.yml
  COPIES=()           # "source path<TAB>dest name" lines to copy into config-repo/dashboards/<source>/

  add_source() {  # name site file... (keys already in .env)
    local name="$1" site="$2" n f info title presets id dest block="" upper
    shift 2
    [[ "$name" =~ ^[a-z0-9]{1,12}$ ]] || fail "source name '$name': use 1-12 lower-case letters or digits, e.g. us or eu"
    for n in "${NAMES[@]:-}"; do [ "$n" != "$name" ] || fail "source name '$name' is used twice"; done
    for n in "${SITES[@]:-}"; do [ "$n" != "$site" ] || fail "site $site is used by two sources; each source needs its own site"; done
    [ $# -gt 0 ] || fail "source '$name': at least one dashboard JSON file is needed"
    upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
    for f in "$@"; do
      [ -f "$f" ] || fail "source '$name': no file $f"
      info="$(inspect_json "$f")"
      [ "${info%%$'\t'*}" = ok ] || fail "source '$name': $f: $info"
      IFS=$'\t' read -r _ title presets id <<< "$info"
      dest="$(basename "$f" | tr ' ' '-' | tr -cd 'A-Za-z0-9._-')"
      case "$dest" in *.json) ;; *) dest="$dest.json" ;; esac
      COPIES+=("$name"$'\t'"$f"$'\t'"$dest")
      block="$block          - file: dashboards/$name/$dest        # $title\n"
      if [ -z "$id" ] && [ -z "${SOURCES_FROM_ENV:-}" ]; then
        read -r -p "    '$title' has no id in the file; its id from the URL, for links (Enter to skip): " id
        [ -z "$id" ] || [[ "$id" =~ ^[a-z0-9-]+$ ]] || fail "'$id' is not a dashboard id"
        [ -z "$id" ] || block="$block            id: $id\n"
      fi
      if [ "$presets" -gt 0 ]; then
        local want="all" var="PRESETS_${upper}"
        if [ -n "${SOURCES_FROM_ENV:-}" ]; then want="${!var:-all}"; else
          read -r -p "    '$title' has $presets saved views. Watch all of them, each as its own view? [Y/n]: " yn
          case "${yn:-y}" in n|N) want=none ;; esac
        fi
        [ "$want" = none ] || block="$block            presets: all\n"
        echo "    $title: $presets saved views $([ "$want" = none ] && echo 'not watched (the file defaults are used)' || echo 'watched')"
      fi
    done
    NAMES+=("$name"); SITES+=("$site"); ENTRIES+=("$block")
  }

  keys_for() {  # name: keys from the environment, else from an existing .env, else asked (hidden); stored in .env
    local name="$1" upper api app var
    upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
    var="DD_${upper}_API_KEY"; api="${!var:-}"; [ -n "$api" ] || api="$(value "$var")"
    var="DD_${upper}_APP_KEY"; app="${!var:-}"; [ -n "$app" ] || app="$(value "$var")"
    if [ -z "${SOURCES_FROM_ENV:-}" ]; then
      [ -n "$api" ] || { read -r -s -p "  DD_${upper}_API_KEY (hidden): " api; echo; }
      [ -n "$app" ] || { read -r -s -p "  DD_${upper}_APP_KEY (hidden): " app; echo; }
    fi
    [ -n "$api" ] || fail "source '$name': the API key (DD_${upper}_API_KEY) is missing"
    [ -n "$app" ] || fail "source '$name': the application key (DD_${upper}_APP_KEY) is missing; both keys are needed"
    put_env "DD_${upper}_API_KEY" "$api"
    put_env "DD_${upper}_APP_KEY" "$app"
    unset api app
  }

  if [ -n "${SOURCES:-}" ]; then
    SOURCES_FROM_ENV=1
    for name in $SOURCES; do
      upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
      site_var="DD_${upper}_SITE"; files_var="DASHBOARD_FILES_${upper}"
      say "Source '$name'"
      keys_for "$name"
      # shellcheck disable=SC2086
      add_source "$name" "${!site_var:-datadoghq.com}" ${!files_var:-}
    done
  else
    say "Datadog sources (one per account/region; input of keys is hidden)"
    while :; do
      default_name="us"; [ "${#NAMES[@]}" -eq 0 ] || default_name="eu"
      default_site="datadoghq.com"; [ "${#NAMES[@]}" -eq 0 ] || default_site="datadoghq.eu"
      read -r -p "Source name [$default_name]: " name; name="${name:-$default_name}"
      read -r -p "  Datadog site [$default_site]: " site; site="${site:-$default_site}"
      keys_for "$name"
      echo "  Dashboards are JSON files: in Datadog open the dashboard > Share > Export dashboard JSON."
      read -p "  Dashboard JSON files (drag them here, separated by spaces): " -a files
      add_source "$name" "$site" "${files[@]:-}"
      read -r -p "Add another source (for example a second region)? [y/N]: " more
      [ "${more:-n}" = "y" ] || [ "${more:-n}" = "Y" ] || break
    done
  fi

  # Read-only check of each source's API key now; the panels are checked after the build (check-dashboards.sh)
  BLOCK=""
  for i in "${!NAMES[@]}"; do
    name="${NAMES[$i]}"; site="${SITES[$i]}"; upper="$(echo "$name" | tr '[:lower:]' '[:upper:]')"
    if [ "$CHECK" = 1 ]; then
      say "Checking source '$name' with Datadog (read-only)"
      api_get "$site" "DD_${upper}_API_KEY" "DD_${upper}_APP_KEY" /api/v1/validate | grep -q '"valid":true' \
        || fail "Datadog ($site) did not accept DD_${upper}_API_KEY"
      echo "  API key: valid for $site"
    fi
    BLOCK="$BLOCK      - name: $name\n        site: $site\n        api-key-env: DD_${upper}_API_KEY\n        app-key-env: DD_${upper}_APP_KEY\n        dashboards:\n${ENTRIES[$i]}"
  done

  # "Today" is the UTC day; times are shown in UTC, India and New York (application.yml, changeable later)
  zone="${TIME_ZONE:-UTC}"
  python3 -I -c 'import sys, zoneinfo; zoneinfo.ZoneInfo(sys.argv[1])' "$zone" 2>/dev/null || fail "unknown time zone: $zone (use names like Asia/Kolkata, UTC, America/New_York)"

  cp -R "$ROOT/config-template" "$ROOT/config-repo"
  python3 -I - "$ROOT/config-repo" "$zone" "$BLOCK" <<'PY'
import pathlib, sys
repo, zone, block = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3].replace("\\n", "\n").rstrip("\n")
for name, old, new in [("regions.yml", "__SOURCES__", block), ("application.yml", "__TIME_ZONE__", zone)]:
    f = repo / name
    f.write_text(f.read_text().replace(old, new))
PY
  for c in "${COPIES[@]}"; do
    IFS=$'\t' read -r src_name src_file dest <<< "$c"
    mkdir -p "$ROOT/config-repo/dashboards/$src_name"
    cp "$src_file" "$ROOT/config-repo/dashboards/$src_name/$dest"
  done
  git -C "$ROOT/config-repo" init -q -b main
  git -C "$ROOT/config-repo" add -A
  summary="${#NAMES[@]} source(s): ${NAMES[*]}"
  git -C "$ROOT/config-repo" -c user.name="setup" -c user.email="setup@localhost" commit -q -m "Initial settings: $summary, time zone $zone"
  echo "config-repo created: $summary, time zone $zone"
fi

# ---- 4. build -----------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  say "Building the six services (first time: a few minutes)"
  (cd "$ROOT/app" && ./mvnw -q -B -DskipTests package)
  echo "built"
fi

# ---- 5. every panel asked once, with the filters of each view ----------------------------------------------
if [ "$CHECK" = 1 ] && [ "$BUILD" = 1 ]; then
  say "Checking every dashboard panel with Datadog (read-only, last 15 minutes)"
  "$ROOT/scripts/check-dashboards.sh" || echo "Some views return no lines: fix their filters or presets in config-repo/, then run scripts/check-dashboards.sh again."
fi

say "Done"
cat <<'EOF'
Start everything:   scripts/system.sh start
Live page:          http://localhost:8104
Check health:       scripts/system.sh status
Stop:               scripts/system.sh stop
EOF
