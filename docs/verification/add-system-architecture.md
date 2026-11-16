# Verification: add-system-architecture

Results of running the spec scenarios against the real skeleton. Filled in as tasks finish; task 5.1
completes it.

## Section 3: Config Service and settings (15 Nov 2026)
Setup: platform running (Kafka 59092, TimescaleDB 55432), Config Service on 8888 reading `config-repo/`
(its own Git repo, post-commit hook installed), the 5 services started from their jars.

| Spec scenario (runtime-configuration) | What was done | Result |
|---|---|---|
| Each service sees only its own settings plus shared ones | Asked the Config Service for `reporter/default`; read the Reporter's `/actuator/settings` | Reporter gets `reporter.yml` + `application.yml` only; no `anomaly.collector` key. **Pass** |
| Add a dashboard to watch | Dashboards are read from `collector.yml` and shown on the Collector's `/actuator/settings` (2 ids) | **Pass** (same mechanism as the poll-interval test below) |
| Poll interval change applies within 60 s, no restart | Committed `poll-interval: 30s` | Collector showed 30 s **1.8 s** after the commit, same process id. **Pass** |
| Typo in a setting is rejected safely | Committed `-5s`, then `-5 seconds` | Both rejected; 30 s stayed in use; health `DEGRADED` naming `poll-interval`; log line "change rejected; still using version 2". Fixing to 15 s returned health to `UP`, version 3. **Pass** |
| No secrets in configuration files | Scanned every file in `config-repo/` for the key and password values from `.env` | None found. **Pass** |
| Startup log shows keys present, never values | Started the Collector with the keys from `.env` (task 4.2) | Log: "API key present, application key present"; all 6 service logs scanned for key and password values: none; settings page has no key field. **Pass** |

Unit tests: `SettingsHolderTest` 6/6, `CollectorSettingsTest` 4/4, `ContractsCheckTest` 1/1.

## Section 4: Service skeletons (15 Nov 2026)
| Task | Check | Result |
|---|---|---|
| 4.1 Health on every service | `scripts/system.sh status` with everything running | config-service 8888, collector 8101, store 8102, detector 8103, notifier 8104, reporter 8105: all `UP`. **Pass** |
| 4.2 Keys only from the environment | Collector log and a scan of all logs | See the startup-log row above. Unit test `DatadogCredentialsTest` 2/2. **Pass** |
| 4.3 Store gRPC on 9102 | Small Java client using the Store's own libraries | Standard health check: `SERVING`; `StoreService.Ping`: `store`. First attempt failed: Spring gRPC starts no server without a service, fixed with the placeholder (design D3). **Pass** |
| 4.4 Notifier RSocket over WebSocket on 7104 | Small Java client (`RSocketRequester`) | `ping` → `pong`; `incidents` stream opens and completes with 0 items. **Pass** |
| 4.5 One start/stop script | `scripts/system.sh start`, restart of one service, then `stop` | Start: all healthy; a second start skips running services; stop: 0 service processes, 0 platform containers; the user's 7 other containers untouched. **Pass** |

## Section 5: final run of every spec scenario (task 5.1, 15 Nov 2026)
Fresh start with `scripts/system.sh start`, then `python3 -I scripts/verify_runtime_configuration.py`
(repeatable: it commits test changes to `config-repo/`, restores the original settings, and exits 0 only if
every scenario passes). Times are measured from just before `git commit`, so they include the post-commit
hook's broadcast. An earlier run started the clock after the commit and showed 0.0 s, which hid that part;
the script was corrected before this run.

| Scenario | Result | Detail |
|---|---|---|
| Each service sees only its own settings plus shared ones | **Pass** | Reporter's groups: anomaly.reporter, anomaly.shared; Collector keys received: 0 |
| Add a dashboard to watch | **Pass** | new id visible after 0.2 s, same process: True |
| Poll interval change (within 60 s, no restart) | **Pass** | 30 s visible after 0.7 s, same process: True |
| Typo in a setting is rejected safely | **Pass** | health DEGRADED after 0.2 s; still using PT30S; problem: anomaly.collector.poll-interval: '-5 seconds' is not a valid duration; logged: True |
| Fixing the setting returns health to UP | **Pass** | UP with 15 s and the original dashboards after 0.7 s |
| No secrets in configuration files | **Pass** | 7 files and the full Git history scanned: none found |
| Startup log shows keys present, never values | **Pass** | presence line found: True; 6 logs scanned for secret values: none found |

**Result: every scenario of `specs/runtime-configuration/spec.md` passes.** Spec limit: 60 s; measured: under
1 s. Unit tests: 13/13 (SettingsHolderTest 6, CollectorSettingsTest 4, DatadogCredentialsTest 2,
ContractsCheckTest 1).
