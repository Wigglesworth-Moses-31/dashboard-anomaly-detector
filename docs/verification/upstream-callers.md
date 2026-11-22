# Verification: upstream-callers (22 Nov 2026)

| Check | Result |
|---|---|
| Config Service tests | Saving makes one commit "settings page: upstreams" and the bus refresh; reading back gives the text and parsed callers; region `asia`, an upstream that is not a list, and a stale commit are refused with nothing written |
| Settings tab, live | The Upstreams editor shows the file (or the starting text), the upstream tags from `detector.yml` and the API tag; saving `asia` showed "regions must be us or eu (found 'asia')"; saving the lab mapping made commit `18ac439` in this machine's config-repo |
| Called by, live | `upstream_service=billing-core`: "Called by (billing-core) · /v1/invoices · latency up 8.7× · needs a reason" |
| Calls, live | `api=/v1/invoices`: "Calls (/v1/invoices) · billing-core · latency up 8× · needs a reason" |
| Anomalies page, live | Rows show "Calls: billing-core (needs a reason)", "Called by: /v1/invoices (needs a reason)" |
| Script errors | None |

The lab mapping saved on this machine (`billing-core: [/v1/invoices]`, `telemetry-ingest: [/v1/telemetry]`) is only
in its own `config-repo`, never in the public repository.
