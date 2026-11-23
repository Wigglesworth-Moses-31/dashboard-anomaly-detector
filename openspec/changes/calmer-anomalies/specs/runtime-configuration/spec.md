## ADDED Requirements

### Requirement: How often a rule judges is a setting
Each rule in `detector.yml` MAY set `judge-every`: one step (`10s`, the default) or a period from 1 to 30 minutes
that divides an hour. `slow-history` (default 2 h) and `slow-min-history` (default 30 min) SHALL set the normal and
the warm-up of slow rules. Invalid values SHALL be refused, keeping the previous settings. A change SHALL apply
without a restart, the affected lines refilled from the Store.

#### Scenario: Back to every step
- **WHEN** the latency rule's `judge-every` is changed from `5m` to `10s` and committed
- **THEN** latency lines are judged every step again within a minute, without a restart

#### Scenario: Invalid period
- **WHEN** `judge-every: 7m` is committed
- **THEN** the settings are refused with "judge-every must be 10s or 1 to 30 minutes dividing an hour", and the previous rules stay

#### Scenario: Old settings repository
- **WHEN** the system starts with a `detector.yml` whose rules have no `judge-every`
- **THEN** the start lists the rules the template judges every 5 minutes, and the old rules keep working, every step
