# Spec Delta

## ADDED Requirements

### Requirement: Messages are kept 7 days, compressed
Every topic SHALL keep its messages 7 days, and every producer SHALL compress its messages (zstd). Existing topics
SHALL be changed in place when the system starts, without losing messages.

#### Scenario: Topic settings
- **WHEN** the topics are described after start
- **THEN** each shows a retention of 7 days

#### Scenario: Smaller on disk
- **WHEN** the same recording is replayed with and without compression
- **THEN** the bytes on disk per message are smaller with compression, and the ratio is written down
