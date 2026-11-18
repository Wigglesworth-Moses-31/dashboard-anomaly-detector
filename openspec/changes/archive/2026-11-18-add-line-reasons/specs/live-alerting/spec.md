# Spec Delta

## MODIFIED Requirements

### Requirement: A reason can be given on the page
Every line of an open or calm incident that is not BAU and has no reason SHALL have a reason box (1 to 500
characters), and the incident SHALL offer "same reason for all" for every such line. Sending SHALL show "saving…"
until the line's (or lines') reason comes back in the incident's next event, then the line shows its reason with the
time; when nothing comes back within 10 seconds, the page SHALL say "not saved" and keep the text. Empty and too long
reasons SHALL be refused on the page.

#### Scenario: Reason given
- **WHEN** a reason is typed for the 404 line of a calm incident and sent
- **THEN** within a second the line shows the reason with the time, in the display time zones, and the incident closes if no other line waits

#### Scenario: Nothing came back
- **WHEN** the Detector does not answer within 10 seconds
- **THEN** the page says "not saved" and the typed reason stays in the box

#### Scenario: Same reason for all
- **WHEN** "same reason for all" is used with one text on an incident with two lines waiting
- **THEN** both lines get that reason

### Requirement: Reasons show in a line's past
A line's history SHALL show that line's own reason for each earlier incident, "BAU" when it was business as usual,
or "—" when none was given.

#### Scenario: Earlier reason
- **WHEN** a line's earlier incident was closed with "deploy rolled back" on that line
- **THEN** that entry of the line's history shows "reason: deploy rolled back"

#### Scenario: Earlier BAU
- **WHEN** a "general" line's earlier incident was BAU
- **THEN** that entry shows "reason: BAU"

### Requirement: Notify when a problem opens
When the viewer has switched it on, the page SHALL show a desktop notification and may play a short sound when a
new incident opens that has at least one line that is not BAU. It SHALL NOT notify for updates, closes, incidents of
only BAU lines, or incidents already open when the page connected.

#### Scenario: New problem while working in another window
- **GIVEN** notifications are switched on
- **WHEN** "upstream billing-core: latency up" opens
- **THEN** one desktop notification with that title appears

#### Scenario: Page opened during an incident
- **WHEN** the page connects while three incidents are open
- **THEN** it shows them without notifying

#### Scenario: Only BAU
- **WHEN** an incident of only "general up" lines opens
- **THEN** no notification appears

## ADDED Requirements

### Requirement: BAU lines are shown as such
Lines marked BAU SHALL be shown dimmed with "BAU" and no reason box; an incident of only BAU lines SHALL be shown
dimmed.

#### Scenario: Mixed incident on the page
- **WHEN** an incident has one errors line and five "general" lines
- **THEN** the five read "BAU", dimmed, and only the errors line has a reason box
