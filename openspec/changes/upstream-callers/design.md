# Design: upstream-callers

**U1 File.** `upstreams.yml` (template: empty `regions`, commented example). Shape: `caller-tag: api` and
`regions: {us|eu: {<upstream>: [<api>, …]}}`. Which tags name an upstream stays the Detector's `upstream-tags`
setting; the Config Service sends that list with the file, so the page needs no copy of it.

**U2 Config Service.** `GET /settings/upstreams` → `{commit, text, caller_tag, upstream_tags, regions}` (parsed with
SnakeYAML's safe constructor; `upstream_tags` read from `detector.yml`). `POST /settings/upstreams {text}` with
`If-Match`: parse, check regions ∈ {us, eu}, each value a list of strings, `caller-tag` a string; then one commit
"settings page: upstreams" through the same `SettingsRepo.apply` as every Settings write, then the bus refresh. The
Notifier's settings proxy already passes `/settings/**` to the Config Service.

**U3 Page.** Loaded with the catalogue (and again after a save). For a line: region = its site's region name;
upstream = the value of its first upstream tag; API = its caller tag's value. "Called by" lists the region's APIs for
that upstream; each API's lines are the catalogue lines of that region whose caller tag equals it; its state is the
worst `lineState` of those lines and its change the largest of their open anomalies. "Calls" is the reverse lookup.
Clicking an API or upstream opens its worst line (or first line). O(lines) per panel open.

**U4 Settings tab.** A section under Regions: a textarea editor (monospace), Check-and-save, the reason on refusal.
