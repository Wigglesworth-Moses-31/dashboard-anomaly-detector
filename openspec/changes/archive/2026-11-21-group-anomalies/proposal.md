# Proposal: group-anomalies

## Why

The user (21 Nov 2026): "need to group anomalies under a service under a dashboard under a region, so it will be well
sorted". Today the counter in the left navigation opens a flat list of metrics; with many regions, dashboards and
services it is hard to see where the trouble is.

## What Changes

| What | How |
|---|---|
| **Anomalies page** | A new entry in the left navigation (and where the counter now leads): every anomaly of the last 24 hours as a tree **Region → Dashboard → Service → anomaly** |
| **Service** | The incident's own group, as the Detector already groups incidents (`group-by` in `detector.yml`: the first of `api`, `upstream_service`, `peer.rpc.service`, `service`, `resource_name` a line has; else the dashboard). Shown as "api /v1/invoices", "service billing", "upstream telemetry-ingest" |
| **Under each anomaly** | Its state (open, needs a reason, closed, past), when it started (every display zone), how long, its lines (signal, direction, peak against normal), each opening that line's side panel on its dashboard |
| **Sorted** | Regions and dashboards by name; services worst first (open, then needs a reason, then closed or past), then by name; anomalies newest first |
| **Counts** | Each region, dashboard and service shows its open and waiting counts; levels collapse and are remembered |
| **Filter** | State chips (Open, Needs a reason, Closed, Past; open and waiting on at first) and a search over service, dashboard and line |
| **Live** | The tree updates as anomalies change (one redraw per frame) |

An anomaly whose lines sit on two dashboards is listed under each, with the same entry.

## Capabilities

### Modified Capabilities
- `dashboard-view`: anomalies grouped by region, dashboard and service; the counter opens that page.

## Impact

`app/notifier/static` only (the page has every anomaly of the last 24 hours already). README.
