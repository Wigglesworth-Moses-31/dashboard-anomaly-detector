# Spec Delta

## Purpose

Lets the user manage each region's dashboards from the page (create, read, update and delete their exported JSON),
checked before anything is saved, each change one commit in the settings repository.

## ADDED Requirements

### Requirement: Regions and dashboards are listed
The Settings tab SHALL list the regions (name, site, whether both keys are present, never the keys) and, per
region, its dashboards: title, file, id, watched saved views (all, the named ones, or none), number of panels and of
saved views.

#### Scenario: Two regions
- **GIVEN** regions `us` and `eu`, `eu` with one dashboard watching all of its 23 saved views
- **WHEN** the Settings tab is opened
- **THEN** both regions are listed with "keys present", and `eu` shows the dashboard with "23 of 23 saved views"

#### Scenario: No key in the answer
- **WHEN** the list is read
- **THEN** no key value appears in the page or in the answer behind it

### Requirement: A dashboard can be opened and downloaded
Opening a dashboard SHALL show its panels by group, its variables with their defaults, its saved views, and the
filters each watched view uses; its JSON SHALL be downloadable as stored.

#### Scenario: Open a dashboard
- **WHEN** a dashboard with groups "API Metrics" and "Upstream Service Metrics" is opened
- **THEN** its panels are listed under those groups, with its variables, saved views and each watched view's filters

### Requirement: A dashboard can be created from an export
The user SHALL be able to upload an exported dashboard JSON for a region, see what it contains (title, panels by
group, saved views), choose which saved views to watch and optionally its id, and add it. Adding SHALL save the file
under the region and its entry in the settings as one commit, and the dashboard SHALL be collected without a restart.

#### Scenario: Add with all saved views
- **WHEN** an export with 23 saved views is uploaded for `eu`, "all" is chosen and Add is pressed
- **THEN** one commit adds `dashboards/eu/<file>.json` and its entry with `presets: all`, and its views are collected within one poll interval

#### Scenario: Same file twice
- **WHEN** a dashboard with a file name already used in that region is added
- **THEN** it is refused with "already exists: update it instead", nothing written

### Requirement: A dashboard is checked before saving
Before a dashboard is created or updated, the page SHALL be able to ask every panel of every chosen view once
(read-only, the last 15 minutes) and show the lines per panel; an upload that is not valid JSON, has no widgets, names
an unknown saved view, or has an id that differs from the one given SHALL be refused with the reason, nothing written.

#### Scenario: Filters that match nothing
- **WHEN** a view's filters match no data and Check is pressed
- **THEN** its panels show "0 lines" before anything is saved

#### Scenario: Not a dashboard
- **WHEN** a file without widgets is uploaded
- **THEN** it is refused with "no widgets; is this an exported dashboard JSON?" and nothing is written

### Requirement: A dashboard can be updated
The user SHALL be able to replace a dashboard's JSON with a newer export, after seeing which panels and saved views it
adds or removes, and to change which saved views are watched or its id; each update SHALL be one commit, applied
without a restart.

#### Scenario: A newer export
- **WHEN** a newer export with one more panel and one saved view fewer is uploaded for an existing dashboard
- **THEN** the page shows "+1 panel, −1 saved view" first, and Update saves it in one commit; the new panel is collected within one poll interval

#### Scenario: Fewer saved views
- **WHEN** the watched saved views are changed from "all" to "Payments, Billing"
- **THEN** one commit changes the entry, and only those two views are collected

### Requirement: A dashboard's JSON can be edited in the page
Edit SHALL open the committed JSON of a dashboard, as it is, in a text editor in the page with line numbers. While
typing, a JSON mistake SHALL be shown with its line and column, and Save SHALL be off until the text is valid JSON.
Check SHALL read the edited text with the Collector's code and show the panels and saved views added or removed. Save
SHALL replace the file as one commit, with the same checks as Update; the dashboard's entry (watched views, filters)
SHALL stay as it was.

#### Scenario: Rename a panel
- **WHEN** a panel's title is changed in the editor, checked and saved
- **THEN** the check shows one panel added and one removed, and one commit "settings page: edit us/shop.json" holds the edited file

#### Scenario: A JSON mistake
- **WHEN** a comma is deleted in the editor
- **THEN** the line and column of the mistake are shown and Save is off until it is fixed

### Requirement: A dashboard can be deleted
After a confirmation, deleting a dashboard SHALL remove its file and its entry in one commit; its lines SHALL stop
being collected within one poll interval.

#### Scenario: Delete
- **WHEN** a dashboard is deleted and the confirmation is accepted
- **THEN** one commit removes the file and the entry, and its lines are no longer collected

### Requirement: Hand edits are never overwritten
A change from the page SHALL be refused when a file it would write has uncommitted changes in the settings repository,
saying so; files the change does not touch SHALL not be committed with it.

#### Scenario: Uncommitted edit
- **GIVEN** an uncommitted hand edit in `collector.yml`
- **WHEN** a dashboard is added from the page
- **THEN** it is refused with "collector.yml has uncommitted changes: commit or discard them first"

### Requirement: A region can be added with its keys
While fewer than two regions exist, the page SHALL let the user add US (`datadoghq.com`) or EU (`datadoghq.eu`) with
its API key and application key. The keys SHALL be checked with Datadog by read-only calls first; when valid, they
SHALL be written only to `.env` on this machine and the region's entry (naming the key variables, never the keys) to
`collector.yml` as one commit. The Collector SHALL start collecting the region without a restart.

#### Scenario: Add EU
- **GIVEN** only US exists
- **WHEN** EU is added with valid keys
- **THEN** `.env` holds the EU keys, `collector.yml` has one new commit with the EU entry and no key, and EU is collected within one poll without a restart

#### Scenario: Wrong keys
- **WHEN** EU is added with an application key Datadog refuses
- **THEN** "Datadog refused the application key" is shown and nothing is written

#### Scenario: At most two
- **GIVEN** US and EU exist
- **WHEN** a third region is requested
- **THEN** the page offers no "Add region" and the settings API refuses it

### Requirement: A region's keys can be replaced
The page SHALL let the user type new keys for a region; they SHALL be checked the same way and only `.env` SHALL
change; the Collector SHALL use them without a restart.

#### Scenario: New keys
- **WHEN** valid new keys are saved for US
- **THEN** `.env` has the new keys, no commit is made, and the next poll uses them

### Requirement: A region can be removed
After a confirmation naming its dashboards, removing a region SHALL remove its entry and its dashboard files in one
commit and its keys from `.env`; its collection SHALL stop within one poll. Its incidents and data SHALL stay until
they age out.

#### Scenario: Remove EU
- **WHEN** EU, with one dashboard, is removed and confirmed
- **THEN** one commit removes its entry and file, `.env` no longer has the EU keys, and EU is no longer polled

### Requirement: Keys are never shown or stored elsewhere
A key SHALL be sent once from its field to `.env`. No page, answer, log line, error message or commit SHALL contain a
key or part of one; the page SHALL show only whether keys are present and valid.

#### Scenario: Looking for a key afterwards
- **WHEN** a region was added and the logs, the settings repository history and every page answer are searched for its keys
- **THEN** nothing is found
