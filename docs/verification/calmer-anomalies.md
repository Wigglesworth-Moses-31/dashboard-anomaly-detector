# Verification: calmer-anomalies (23 Nov 2026)

## Detector: two speeds of judging

| Check | How | Result |
|---|---|---|
| A 30-second dip opens nothing | Success rate 99% → 60% for 3 steps, then 99% (`SlowJudgingTest`) | No anomaly: the 5-minute average is about 95% |
| A dip that lasts opens one | 60% for a whole 5 minutes | One anomaly, decided by the next period's first value, starting at the period's start |
| Nothing is decided early | Values inside the period | No change until the period is complete |
| Calm after one average | One calm 5-minute average after the dip | The anomaly becomes calm; its end is that period's start |
| Errors stay fast | 500 errors jump for 2 steps | Opens on the second step, as before |
| One odd latency step | 1213 ms once among 140 ms | Averaged away (about 175 ms), nothing opens |
| Warm start | 3 h of Store values | The line asks for 2 h (`slow-history`), fills 24 averages, and is judged on its first live period |
| Rule moved back to 10 s | Settings change at run time | The line asks for a refill from the Store and then judges every step |
| Bad setting | `judge-every: 7m`, `slow-history: 10m` | Refused with the reason; previous settings kept |
| Shipped rules | `config-template/detector.yml` | success-rate, latency ×3, saturation, traffic, general: 5m. error-rate, errors: 10s |
| Recorded scenarios | 42 scenario tests with the rules back at 10 s | All pass unchanged |

## Settings

| Check | Result |
|---|---|
| `scripts/check-config.sh` with an old `config-repo/detector.yml` | "judges every rule every 10 s. The template judges these on 5-minute averages: general latency saturation success-rate traffic", and how to take it |
| Local `config-repo` updated | One commit in config-repo; the check is then silent |
| `/judging.json` (Notifier, read from the Config Service) | `{"success-rate":"5m","error-rate":"10s","errors":"10s","latency":"5m","saturation":"5m","traffic":"5m","general":"5m"}` |
| `/dash/range` | 404 (removed) |

## Page (headless Chrome against the running system)

| Check | Result |
|---|---|
| Look | Top bar, tabs Anomalies · Settings, day cards, Open / Needs a reason / Closed today, cards with coloured edges, as in the 16-18 Nov page; light and dark |
| Real anomalies | 12 open and 7 waiting from the lab days, grouped under Region › Dashboard, worst first; badge "7" on the Anomalies tab |
| Judged tag | "5-min average" or "every 10 s" per card, from `/judging.json` |
| Graph of an anomaly | Card titled Panel › Graph; unfolded: all its lines, its worst line bold red, one labelled band, a peak dot |
| Split graph | A test anomaly on `status=200`: only that small graph's line is drawn; "Other graphs of Panel 0 · 3 graphs" folded under it, drawn on unfolding |
| Grouped by service (older anomalies) | One graph per panel with that service's lines |
| No values in range | The graph says "No values in the past 1 hour" (lab traffic is off) |
| Called by | US billing-core: "Called by · /v1/invoices (needs a reason)", with the callers' graphs of the same kind |
| Show one line | Full screen: picking `status=201` draws only that line; the other rows are dimmed |
| Zoom | − / + at 150%: graphs 330 px high instead of 220 px |
| Side panel | Line details, its anomaly, reason boxes for the line and "same reason for all 6 lines"; the top bar keeps its size |
| Memory | `__benchCards(200, 10)`: 200 unfolded cards, 2,000 lines, at most 11 charts alive, 114 MB of browser memory (limit 200 MB) |

## Tests

| Module | Ran | Failures |
|---|---|---|
| contracts | 28 | 0 |
| service-kit | 8 | 0 |
| collector | 123 | 0 |
| store | 6 | 0 |
| detector | 77 (9 new) | 0 |
| notifier | 32 (2 new) | 0 |
| config-service | 12 | 0 |

Live tests that need a running platform and a flag were skipped, as always.

## Live, with lab traffic (23 Nov, 13:04 to 13:37 UTC)

The traffic generator's 3-minute bursts (500 errors and slow `/v1/invoices`, every ~8 minutes) ran for 33 minutes.

| Check | Result |
|---|---|
| Errors stay fast | 7 "errors up" anomalies, each starting at its burst's own time and saved 82 to 95 s later: 2 steps (20 s) plus Datadog's delay and the Collector's settle delay, as before this change |
| One anomaly per graph | One per graph per burst, no open/close flapping |
| 5-minute lines | Not judged yet when the check stopped: with an empty Store they need 30 minutes of history (`slow-min-history`), so they would start at about 13:36 |
| Silent lines | 2 "silent" anomalies at 13:18 (lines that stopped while the lab started up). Making every silence BAU is the separate change `silent-is-bau` |

## Not checked yet

The 5-minute judging on live data: let the lab run for at least 45 minutes, then expect latency and success-rate
anomalies starting on 5-minute marks, and none from short wobbles between bursts.
