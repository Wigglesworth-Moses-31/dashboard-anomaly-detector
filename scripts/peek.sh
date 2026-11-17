#!/usr/bin/env bash
# Look at what metrics are coming in, straight from the database and Kafka (read-only).
#   scripts/peek.sh panels            every dashboard / panel: how many lines, when its newest value arrived
#   scripts/peek.sh panel <text>      newest value of each line on panels whose title contains <text>
#   scripts/peek.sh flowing           is data arriving right now? (values in the last 5 minutes, how fresh)
#   scripts/peek.sh watch             'flowing' every 10 seconds (Ctrl-C to stop)
#   scripts/peek.sh kafka             messages per topic, and how far behind each reader is
# Needs the platform running (scripts/system.sh start). Raw 10-second values are kept a few hours (the Reporter
# removes them after each report); per-minute values 2 days, so 'panel' falls back to per-minute values.
set -euo pipefail

sql() {
  docker exec anomaly-timescaledb sh -c 'psql -U "$POSTGRES_USER" -d "${POSTGRES_DB:-postgres}" -P pager=off -c "$1"' sh "$1"
}

case "${1:-}" in
  panels)
    sql "select v->>'view_title' as dashboard, v->>'panel_title' as panel, count(distinct s.series_id) as lines,
                max(p.newest) as newest_value_at
         from metrics.series s
         cross join lateral jsonb_array_elements(s.views) v
         left join lateral (select max(ts) as newest from metrics.points where series_id = s.series_id) p on true
         group by 1, 2 order by 1, 2"
    echo "A panel with lines but no newest_value_at: its queries return nothing now (no traffic, or a filter that matches nothing)."
    ;;
  panel)
    [ -n "${2:-}" ] || { echo "usage: $0 panel <part of a panel title>" >&2; exit 2; }
    text="${2//\'/}"                                            # no quotes inside the SQL text
    sql "select s.metric, s.group_tags, coalesce(r.ts, m.minute) as at, round(coalesce(r.value, m.max)::numeric, 2) as value,
                s.unit, case when r.ts is not null then '10 s' when m.minute is not null then 'per minute (max)' else 'no data' end as resolution
         from metrics.series s
         left join lateral (select ts, value from metrics.points where series_id = s.series_id order by ts desc limit 1) r on true
         left join lateral (select minute, max from metrics.points_1m where series_id = s.series_id order by minute desc limit 1) m on true
         where s.views::text ilike '%${text}%'
         order by s.metric, s.group_tags::text limit 50"
    ;;
  flowing)
    sql "select count(*) as values_last_5_min, count(distinct series_id) as lines_last_5_min,
                now() - max(ts) as newest_is_this_old
         from metrics.points where ts > now() - interval '5 minutes'"
    ;;
  watch)
    while :; do date +%T; "$0" flowing | sed -n '3p'; sleep 10; done
    ;;
  kafka)
    for topic in metric-series metric-points incident-events; do
      total=0
      while IFS=: read -r _ _ offset; do total=$((total + offset)); done \
        < <(docker exec anomaly-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic "$topic" 2>/dev/null)
      echo "$topic: $total messages so far"
    done
    echo "Readers (LAG = messages not read yet; 0 = keeping up):"
    docker exec anomaly-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null \
      | awk '$1 != "" && $1 != "GROUP" && $1 !~ /^dump-/ && $2 ~ /^(metric|incident)/ {lag[$1" "$2] += $6} END {for (k in lag) print "  " k ": LAG " lag[k]}' | sort
    ;;
  *)
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
