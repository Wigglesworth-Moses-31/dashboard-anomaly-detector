# Tasks: remove-reporter
- [x] 1.1 Delete `app/reporter`; remove it from `app/pom.xml`
- [x] 1.2 `scripts/system.sh`: five services; `scripts/peek.sh` and `verify_runtime_configuration.py` without the Reporter
- [x] 1.3 Delete `config-template/reporter.yml`; `config-template/README.md`; check-config keys without `catch-up`
- [x] 1.4 Store contract comments: `GetDaySummary` and `RemoveRawBefore` unused; other code comments that name the Reporter
- [x] 1.5 README and docs (architecture, ports, settings, upgrading)
- [x] 1.6 Build and all tests; stop the Reporter on this machine; status shows five services
