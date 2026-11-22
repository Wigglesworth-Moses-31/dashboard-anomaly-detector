# Proposal: upstream-callers

## Why

The user (22 Nov 2026): "we have upstream metrics; we need details of which APIs are calling this specific upstream,
so if we see an anomaly for a specific upstream service we can see which API is affected as well". Asked how the
system should know the callers, the user chose: "we should do the configuration on our own".

The upstream panels are collected `by {upstream_service}` only, so the data cannot tell which APIs call an upstream.

## What Changes

| What | How |
|---|---|
| **A mapping you keep** | New `config-repo/upstreams.yml`: per region, each upstream service and the APIs that call it. Changes are commits, like every setting |
| **Edited in the page** | Settings tab gets an **Upstreams** section: per region a YAML editor with check and save (one commit per save), like the dashboard JSON editor |
| **Which tags** | The upstream's name is the value of an upstream tag (`upstream-tags` in `detector.yml`: `upstream_service`, `peer.rpc.service`); the API's name is the value of a caller tag (new setting, default `api`) |
| **Shown on an upstream anomaly** | Side panel, Worst anomalies and Anomalies page: **"Called by"**: each API with its state now (open, needs a reason, fine) and its change, linking to its line on its graph |
| **Shown on an API anomaly** | **"Calls"**: the upstreams it depends on and their state, so a slow API leads to a slow upstream too |
| **Unknown upstream** | An upstream not in the file says "Callers not configured: add them in Settings › Upstreams" |

Example `upstreams.yml`:

```yaml
caller-tag: api
regions:
  us:
    billing-core: [/v1/invoices, /v1/payments]
    telemetry-ingest: [/v1/telemetry]
```

## Capabilities

### Modified Capabilities
- `runtime-configuration`: `upstreams.yml`, served by the Config Service and written by the Settings tab.
- `settings-page`: Upstreams section.
- `dashboard-view`: "Called by" and "Calls" on anomalies.

## Impact

`config-template/upstreams.yml`, `app/notifier` (settings API for the file, page), `README.md`. No Datadog calls, no
contract change, no Detector change.
