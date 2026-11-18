# Spec Delta

## MODIFIED Requirements

### Requirement: A time range can be chosen
The region, panel and metric pages SHALL offer 15m and 1h (10-second values, moving live) and 4h, 1d, 7d and 10d
(per-minute values, labelled "per minute"). A range longer than the data kept SHALL show what is kept and say so.

#### Scenario: Long range
- **WHEN** 10d is chosen while 2 days are kept
- **THEN** the charts show 2 days of per-minute values and say "2 days kept"

### Requirement: Dashboard view settings live outside the code
The ranges offered, the default range, the most points per line and the most lines per panel SHALL be settings in
`notifier.yml`, changed without a restart; a range longer than 10 days SHALL be refused.

#### Scenario: Range beyond what is kept
- **WHEN** a 30-day range is added to the settings
- **THEN** the setting is refused and the previous ranges stay
