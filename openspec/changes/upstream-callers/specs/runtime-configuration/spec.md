## ADDED Requirements

### Requirement: Upstream callers are a settings file
`config-repo/upstreams.yml` SHALL list, per region, each upstream service and the APIs that call it, with `caller-tag`
naming the tag whose value is an API (default `api`). A missing file SHALL mean no callers are known. The file holds no
secrets.

#### Scenario: Example
- **WHEN** the file says `regions: { us: { billing-core: [/v1/invoices, /v1/payments] } }`
- **THEN** in US, `billing-core` is called by `/v1/invoices` and `/v1/payments`
