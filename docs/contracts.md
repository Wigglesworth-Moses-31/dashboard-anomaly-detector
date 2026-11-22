# Message contracts

Every message the services exchange, in plain words. The source of truth is the `.proto` files in
`app/contracts/src/main/proto/lab/anomaly/v1/`; design reasons are in the `add-contracts` change.
**The JSON examples below are produced by `ContractJson` and checked by a test (`DocsExamplesTest`), so they
always match the real contracts.** Refresh them after a contract change with
`mvn -pl contracts test -Dcontracts.updateDocs=true` (from `app/`).

## At a glance
| Message | From → To | Travels on | Key | How often |
|---|---|---|---|---|
| `SeriesDescriptor` | Collector → Store, Detector | Kafka `metric-series` (compacted) | `series_id` | When a line is first seen or changes |
| `MetricPoints` | Collector → Store, Detector | Kafka `metric-points` | `series_id` | Every poll (about every 15 s per line) |
| `IncidentEvent` | Detector → Notifier | Kafka `incident-events` | `incident_id` | When an incident opens, changes or closes |
| `StoreService` | Collector, Detector, Notifier → Store | gRPC, port 9102 | - | On demand |
| `incidents.live` | Notifier → web page | RSocket over WebSocket, port 7104 | - | Live |

## Rules every message follows
1. Times are UTC milliseconds since 1 January 1970 (`..._ms` fields).
2. A step with no data is left out, never sent as 0.
3. The newest, unfinished step is sent with `partial: true`; a later poll sends it complete.
4. Every enum starts with `..._UNSPECIFIED` (0), so "never set" is visible.
5. Fields are only ever added. Removing one means reserving its number. The build's compatibility guard
   (`ContractCompatibilityTest`) fails on anything else.
6. Readers ignore fields they do not know, and run `ContractChecks` before using a message.

## `series_id`: how a metric line is identified
First 16 hex characters of SHA-256 over: source system, source instance, query id, then each tag name and
value with tags sorted by name, each piece written as `<UTF-8 byte length>:<text>`. Same line → same id, in
any tag order; any difference → different id. `query_id` is the same hash over the query text with spaces
collapsed. Reference values (checked in `SeriesIdsTest`, also computed in Python):
`queryId("sum:lab.api.request.count{env:prod} by {api,status_code}.as_count()") = b48b75587ccb3b95`.

## SeriesDescriptor: what one metric line is
Sent rarely, so the frequent `MetricPoints` can stay small (they refer to it by `series_id`).

<!-- example: series-descriptor -->
```json
{
  "series_id": "cd2f4d07f1267ff9",
  "source": {
    "system": "datadog",
    "instance": "datadoghq.com"
  },
  "query_id": "b48b75587ccb3b95",
  "query_text": "sum:lab.api.request.count{env:prod} by {api,status_code}.as_count()",
  "metric": "lab.api.request.count",
  "group_tags": {
    "api": "/v1/invoices",
    "status_code": "500"
  },
  "space_aggregation": "AGGREGATION_SUM",
  "time_aggregation": "AGGREGATION_MAX",
  "step_seconds": 10,
  "kind": "KIND_COUNT",
  "unit": "requests",
  "views": [{
    "view_id": "w2f-pqs-ghd",
    "view_title": "Lab: API platform (skeleton structure)",
    "panel_title": "Count"
  }],
  "classified_by": "rule:2",
  "first_seen_ms": "1763182800000",
  "updated_ms": "1763182800000"
}
```
Each view says where the panel sits: its group (`panel_path`, add-incident-history) and, added by add-dashboard-view,
the group's and the panel's position on the dashboard in Datadog's order (`group_index`, `panel_index`; 0 = not known,
for lines described before). The live pages draw dashboards in that order.

## MetricPoints: the newest numbers of one line
Oldest point first. Here the last step was still filling up when collected, so it is `partial`.
Two things about the JSON form (both are Protobuf's standard JSON rules):
- Fields at their default (`false`, `0`, empty) are not printed, which is why only the last point shows
  `partial`. That is different from a step with no data, which has no point at all.
- 64-bit numbers such as `collected_ms` and `ts_ms` are written as text (`"1763182815412"`), so JavaScript,
  whose numbers are not exact beyond 2^53, never loses precision.

<!-- example: metric-points -->
```json
{
  "series_id": "cd2f4d07f1267ff9",
  "collected_ms": "1763182815412",
  "points": [{
    "ts_ms": "1763182790000",
    "value": 3.0
  }, {
    "ts_ms": "1763182800000",
    "value": 42.0
  }, {
    "ts_ms": "1763182810000",
    "value": 17.0,
    "partial": true
  }]
}
```

## IncidentEvent: an incident opened, updated or closed
Carries the incident's full current state, so a viewer needs only the latest event. `event_id` identifies
a duplicate delivery; `sequence` lets a reader ignore an older event that arrives late.
Added by add-detector (additions only): per line `signal` (errors, traffic, success-rate, error-rate, latency,
saturation, general), `reason` (up, down, silent), `dashboard_title`, `panel_title`, `panel_path` (the panel's group or
section, add-incident-history); per incident `group_key`, `reason` and `resolved_ms` (add-incident-resolution: the reason given
when closing, and when; state `INCIDENT_STATE_CALM` = all lines normal again, waiting for a reason). Topic `incident-commands`
carries `ResolveIncident` (command id, incident id, reason of 1–500 characters, and since add-line-reasons the line,
`series_id`, empty for every line still waiting), read only by the Detector. Each affected line carries `explanation`,
`explained_ms` and `bau` (add-line-reasons: a person's reason for that line; `reason` on a line is its direction)
(what the lines have in common, e.g. `api=/v1/invoices`) and `upstream` (the group is a service being called).

<!-- example: incident-event -->
```json
{
  "event_id": "5b6f1d0e-8a1c-4c55-9c7b-2f0d3e9a4b11",
  "incident_id": "inc-20261115-0905-7c2e",
  "sequence": "2",
  "type": "EVENT_TYPE_UPDATED",
  "occurred_ms": "1763182830000",
  "incident": {
    "incident_id": "inc-20261115-0905-7c2e",
    "state": "INCIDENT_STATE_OPEN",
    "title": "/v1/invoices: errors up",
    "started_ms": "1763182800000",
    "series": [{
      "series_id": "cd2f4d07f1267ff9",
      "metric": "lab.api.request.count",
      "group_tags": {
        "api": "/v1/invoices",
        "status_code": "500"
      },
      "peak_value": 42.0,
      "peak_ts_ms": "1763182800000",
      "normal_value": 3.0,
      "unit": "requests",
      "signal": "errors",
      "reason": "up",
      "dashboard_title": "Lab: API platform (skeleton structure)",
      "panel_title": "Count"
    }],
    "links": [{
      "label": "Lab: API platform (skeleton structure)",
      "url": "https://app.datadoghq.com/dashboard/w2f-pqs-ghd?from_ts\u003d1763182500000\u0026to_ts\u003d1763183100000"
    }],
    "group_key": "api\u003d/v1/invoices"
  }
}
```

## StoreService (gRPC)
| Method | Asks | Answer |
|---|---|---|
| `Ping` | Are you there? | `service`, `time_ms` |
| `GetHistory(series_id, from_ms, to_ms, resolution)` | Points of one line in a time range, raw (10 s) or per minute | A stream of chunks, at most 1000 points each, oldest first |
| `GetDaySummary(date, time_zone)` | Every line's summary for one local day (midnight to midnight in that zone) | A stream: `count`, `min`, `max`, `mean`, `p95` per line |
| `GetSummary(from_ms, to_ms)` | Every line's summary for **any window** `[from, to)`, e.g. the last 6 hours (added by add-store) | A stream of `SeriesSummary`: the same numbers plus the window |
| `ListSeries()` | Every line the Store knows (added by add-dashboard-view: the Notifier's catalogue of dashboards and panels) | A stream of `SeriesDescriptor` |
| `GetHistoryBatch(series_ids, from_ms, to_ms, resolution)` | Many lines (at most 1000) over one range in one question (add-dashboard-view) | A stream of chunks, each naming its line; per minute, each minute gives its lowest value at the minute and its highest 30 s later |
| `RemoveRawBefore(before_ms)` | Remove raw points older than a time (added by add-reporter; unused since remove-reporter). Never newer than 3 hours ago, which the per-minute summaries are refreshed from; a time in the future is refused | `removed_points`, `effective_before_ms` (the time actually used) |

## incidents.live (RSocket)
Request-stream on `ws://localhost:7104`: first every incident of the day (open and closed, open first), then one
line `{"snapshot_complete":true,"day":"2026-11-16","time_zone":"Asia/Kolkata","day_by":"started"}`, then each new `IncidentEvent`, as
one-line JSON with the contract's field names. Route `ping` answers `pong`.
Added by add-notifier: the day's closed incidents in the first part, and the `snapshot_complete` line, which tells a
page which incidents were already there when it connected (no notification for those) and which day and time zone
it shows. The stream ends normally at midnight and when the time zone setting changes; the page reconnects and gets
the new day. A reader that ignores lines it does not know is unaffected.
