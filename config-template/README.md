# Settings (config-repo)

`scripts/setup.sh` copies this folder to `config-repo/`, fills in your Datadog sources (site, key names, dashboards) and time zone, and makes
it its own Git repository. The Config Service reads the **committed** version of those files and hands each service
its settings: nothing is fixed in code.

| File | Read by | What you usually change |
|---|---|---|
| `application.yml` | every service | `anomaly.shared.time-zone` (the page and the reports) |
| `collector.yml` | Collector | `dashboards`, `dashboard-filters`, `classification` |
| `detector.yml` | Detector | `rules`, `group-by` |
| `store.yml` | Store | storage limits (2 days by default, at most 7) |
| `notifier.yml` | Notifier | `day-by` |

## Change a setting (no restart)
```bash
cd config-repo
# edit a file, then:
git commit -am "watch one more dashboard"
```
A post-commit hook asks the Config Service to broadcast the change; every running service has it within seconds.
A change that breaks a rule (for example a 48-hour report window) is refused and the old value stays; the service's
`/actuator/settings` page says why.

`dashboards/` holds the exported dashboard JSON files, one folder per source. Edit a file (for example a variable's
`default`) or replace it with a new export, check it with `scripts/check-dashboards.sh`, then commit.
