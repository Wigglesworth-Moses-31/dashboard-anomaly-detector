# Tasks

- [x] 1.1 Store: migration V3 (chunk interval, compression, retention policies), settings 10 days, sizes on health (design T1)
- [x] 1.2 Reporter: no raw clean-up after a report; catch-up 10 days (design T2)
- [x] 1.3 Kafka: 10-day retention for new and existing topics; zstd in every producer (design T3)
- [x] 1.4 Detector, Notifier, page: 10 days, bounded line index, 10d range (design T4)
- [x] 1.5 Tests; live: compression ratio measured; README storage table and upgrade note; `docs/verification/keep-ten-days.md`
