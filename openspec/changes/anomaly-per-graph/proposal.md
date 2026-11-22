# Proposal: anomaly-per-graph

## Why

The user (22 Nov 2026, photo of a real dashboard): "Seeing only 1 anomaly but they are present in different graphs and
different metrics panels; anomalies should be per graph per metrics panel per dashboard per region".

Today the Detector puts misbehaving lines into one anomaly by their first service tag (`group-by`: `api`,
`upstream_service`, `peer.rpc.service`, `service`, `resource_name`); a line with none of them goes into **one anomaly
for its whole dashboard**. A dashboard whose lines are tagged by client, partner or status code therefore shows a
single "(no service tag)" anomaly with 30+ lines from many panels, open for more than a day, with one reason for all.

## What Changes

| What | How |
|---|---|
| **One anomaly per graph** | An anomaly belongs to one **region › dashboard › panel › graph**. A widget is one graph; each small graph of a split group (one per `api`, client, …) is its own graph. Lines of the same graph that misbehave together stay one anomaly with one reason |
| **Which graph a line belongs to** | Its first place on the dashboard, in Datadog's order. A line also drawn on another graph shows its band there too, but belongs to one anomaly |
| **Split groups** | The Collector reads each split group's split tag (`split_config.split_dimensions[0].one_graph_per`) and sends it with the line's place (`View.split_tag`, a new field), so the Detector knows which small graph a line is on |
| **A setting, not code** | `detector.yml`: `incident-scope: graph` (new default) or `service` (today's behaviour, using `group-by`) |
| **Titles** | "US · Shop · Production › Request Counts by Partner › partner=…" style: region, dashboard, panel, graph, then what happened |
| **Anomalies page** | Tree **region › dashboard › panel › anomaly** (the service tag stays as a label and in the search) |
| **Worst anomalies** | The "Service" column becomes "Graph" |
| **Anomalies already open** | Kept as they are until they close; new ones are per graph |

## Capabilities

### Modified Capabilities
- `anomaly-detection`: one incident per graph, chosen by a setting.
- `metric-collection`: a line's place names its split tag.
- `message-contracts`: `View.split_tag` added.
- `dashboard-view`: the Anomalies tree and Worst anomalies by panel and graph.

## Impact

`app/contracts` (one field), `app/collector` (dashboard reader), `app/detector` (`IncidentBook` grouping, titles),
`app/notifier` page, `config-template/detector.yml`. No data wipe.
