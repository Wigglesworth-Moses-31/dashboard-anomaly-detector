#!/usr/bin/env bash
# Lists settings where config-repo still has an older value than config-template (fix-backfill-faults F6).
# It only reads: config-repo holds your own settings and is never changed here. These files hold no secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYS="raw-safety-limit summary-retention history-max-range max-database-size backfill-new-views settle-delay incident-retention"

value() {  # file key -> first value of that key, without its comment
  sed -n -E "s/^[[:space:]]*$2:[[:space:]]*([^#]*).*/\1/p" "$1" 2>/dev/null | head -1 | sed -E 's/[[:space:]]+$//'
}

found=0
for tmpl in "$ROOT"/config-template/*.yml; do
  mine="$ROOT/config-repo/$(basename "$tmpl")"
  [ -f "$mine" ] || continue
  for key in $KEYS; do
    want="$(value "$tmpl" "$key")"
    [ -n "$want" ] || continue
    have="$(value "$mine" "$key")"
    [ -n "$have" ] || continue          # missing: the service's default applies, which matches the template
    if [ "$have" != "$want" ]; then
      [ $found = 0 ] && echo "Settings older than the templates (edit, then commit in config-repo):"
      printf '  %-20s yours: %-8s template: %-8s file: config-repo/%s\n' "$key" "$have" "$want" "$(basename "$tmpl")"
      found=1
    fi
  done
done
exit 0
