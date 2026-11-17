#!/usr/bin/env bash
# Saves dashboards' JSON by id, for people whose application key may read dashboards (dashboards_read). Read-only.
#   scripts/export-dashboards.sh <source> <dashboard-id>...
# The source's site and keys come from config-repo/collector.yml and .env (DD_<SOURCE>_API_KEY / _APP_KEY).
# Files go to config-repo/dashboards/<source>/<id>.json; the '- file:' lines to add to collector.yml are printed.
# Everyone else: in Datadog, open the dashboard > Share > Export dashboard JSON, and save the file there.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ $# -ge 2 ] || { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
source_name="$1"; shift
upper="$(echo "$source_name" | tr '[:lower:]' '[:upper:]')"
value() { grep -E "^$1=" "$ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2- || true; }
api_name="DD_${upper}_API_KEY"; app_name="DD_${upper}_APP_KEY"
[ -n "$(value "$api_name")" ] || { api_name=DD_API_KEY; app_name=DD_APP_KEY; }
[ -n "$(value "$api_name")" ] && [ -n "$(value "$app_name")" ] || { echo "No keys for source '$source_name' in .env" >&2; exit 1; }
site="$(awk -v n="$source_name" '$0 ~ "- name: *"n"([ #]|$)" {f=1; next} f && /site:/ {print $2; exit} f && /- name:/ {exit}' "$ROOT/config-repo/collector.yml" 2>/dev/null || true)"
site="${site:-${SITE:-$(value DD_SITE)}}"; site="${site:-datadoghq.com}"
headers="$(mktemp)"; trap 'rm -f "$headers"' EXIT; chmod 600 "$headers"
printf 'DD-API-KEY: %s\nDD-APPLICATION-KEY: %s\n' "$(value "$api_name")" "$(value "$app_name")" > "$headers"
dir="$ROOT/config-repo/dashboards/$source_name"; mkdir -p "$dir"
echo "Add to collector.yml under source '$source_name' -> dashboards:"
for id in "$@"; do
  [[ "$id" =~ ^[a-z0-9-]+$ ]] || { echo "  skipped '$id': not a dashboard id" >&2; continue; }
  if curl -fsS -m 30 -H @"$headers" "https://api.$site/api/v1/dashboard/$id" | python3 -I -m json.tool > "$dir/$id.json.tmp"; then
    mv "$dir/$id.json.tmp" "$dir/$id.json"
    title="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("title","?"), "|", len(d.get("template_variable_presets") or []), "saved views")' "$dir/$id.json")"
    echo "  - file: dashboards/$source_name/$id.json      # $title"
  else
    rm -f "$dir/$id.json.tmp"; echo "  could not read dashboard $id on $site (id, site, or dashboards_read scope)" >&2
  fi
done
