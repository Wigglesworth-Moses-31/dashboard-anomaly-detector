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
# calmer-anomalies: rules with judge-every in the template but none in config-repo are still judged every 10 s there.
tmpl="$ROOT/config-template/detector.yml"; mine="$ROOT/config-repo/detector.yml"
if [ -f "$mine" ] && grep -q 'judge-every:' "$tmpl" && ! grep -q 'judge-every:' "$mine"; then
  slow="$(awk '/^ *- signal:/{s=$3} /^ *general:/{s="general"} /judge-every: *[0-9]+m/{print s}' "$tmpl" | sort -u | tr '\n' ' ')"
  echo "config-repo/detector.yml judges every rule every 10 s. The template judges these on 5-minute averages:"
  echo "  $slow"
  echo "  To take it: copy the judge-every, steps, slow-history and slow-min-history lines from config-template/detector.yml, then commit in config-repo."
fi
exit 0
