# Tasks

Choices D5 (Spring Cloud Config) and D8 (Java 25) were approved by the user on 15 Nov 2026.
Installing a JDK still needs the user's OK at that moment.

## 1. Tools and project layout

- [x] 1.1 Install JDK 25 (approved choice D8) and verify `java -version` in the project shows it, while the Mac's other projects keep their JDK
- [x] 1.2 Create the Maven parent project in `app/` with modules `contracts`, `config-service`, `collector`, `store`, `detector`, `notifier`, `reporter` (Spring Boot 4.0.9, see design D9) and verify `mvn -q verify` succeeds on the empty modules
- [x] 1.3 Add a `.gitignore` that excludes `.env`, build output and IDE files, and verify `git status` shows no secret or build file
- [x] 1.4 Set up the `contracts` module to generate Java from `.proto` files with one placeholder message, and verify a service module can use the generated class in a compiling test

## 2. Local platform (Kafka and TimescaleDB)

- [x] 2.1 Write `platform/docker-compose.platform.yml` with single-node Kafka (port 59092) and TimescaleDB (port 55432), and verify both report healthy while the user's other containers keep running
- [x] 2.2 Create the topics `metric-series` (compacted), `metric-points` and `incident-events` with the retention from design.md D2, and verify with the Kafka topic listing
- [x] 2.3 Create the database schemas `metrics` and `incidents` with the Timescale extension enabled, and verify with a SQL check that the extension is active
- [x] 2.4 Document start and stop of the platform in `docs/platform.md` and verify the documented commands work as written

## 3. Config Service and settings

- [x] 3.1 Create `config-repo/` with `application.yml` (shared) and one YAML file per service holding the first settings (dashboards, poll interval, step, report time, retention), and verify no file contains a key value
- [x] 3.2 Build the Config Service (Spring Cloud Config Server reading `config-repo/` from Git) and verify it serves the Collector's merged settings on its HTTP endpoint
- [x] 3.3 Make every service load its settings from the Config Service at start into an immutable settings snapshot (design D13) and show them on a settings endpoint, and verify the Reporter does not receive the Collector's settings
- [x] 3.4 Add live reload over Kafka (Spring Cloud Bus), and verify a committed poll-interval change appears on the Collector's settings endpoint within 60 seconds without a restart
- [x] 3.5 Add settings validation as a chain of validators that swaps in the new snapshot only when all pass (design D13), and verify a poll interval of "-5 seconds" is rejected, logged and shown in the health status while the old value stays in use

## 4. Service skeletons

- [x] 4.1 Give each of the 6 services a health endpoint on its port from design.md D12, and verify all 6 report healthy together
- [x] 4.2 Read the Datadog keys only from the environment in the Collector, and verify its startup log says the keys are present without printing them
- [x] 4.3 Add the Store's gRPC server on port 9102 with a placeholder `StoreService.Ping` (Spring gRPC starts only when a service exists) and verify a gRPC health check answers
- [x] 4.4 Add the Notifier's empty RSocket endpoint over WebSocket on port 7104 and verify a test client can connect
- [x] 4.5 Add one script (`scripts/system.sh`) to start and stop the platform and all 6 services, and verify a full start then stop leaves no service process running

## 5. Wrap-up checks

- [x] 5.1 Run every scenario (`scripts/verify_runtime_configuration.py`) in `specs/runtime-configuration/spec.md` against the running skeleton and record the results in `docs/verification/add-system-architecture.md`
- [x] 5.2 Update `HANDOFF.md` with the new layout, ports and commands, and verify a fresh reader can start the system from it

## Workflow follow-up

- The user reviews and approves this change before any task starts.
- After the tasks are done, the user explains the architecture back in a few sentences; then archive.
