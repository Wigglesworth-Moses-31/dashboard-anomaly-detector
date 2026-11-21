# Proposal: add-datadog-look

## Why

The user (21 Nov 2026): "the graph we built is not similar to Datadog; we need to build a clone of Datadog: look and
feel, UI and navigation should be the same". The pages of `add-dashboard-view` draw every panel as a line chart in a
simple grid, in our own style. People who live in Datadog should feel at home: the same layout, the same widget
types, the same way of moving around.

**What "the same" means here:** Datadog's layout, behaviour and navigation patterns, rebuilt in our own page. Not
copied: Datadog's logo, name, fonts, icons or other artwork. The page keeps its own name.

## What Changes

### Navigation (decided with the user)

| Datadog | Here |
|---|---|
| Left navigation bar | A slim left bar: **Dashboards**, one entry per region (**US**, **EU**), **Settings**, and the **attention** counter (open · need a reason) |
| Dashboard list (search, title, last modified) | Per region: a searchable list of its dashboards with widget counts and badges for open and waiting anomalies |
| A dashboard | The dashboard **as laid out in its JSON**: the 12-column grid with each widget's position and size; dashboards without positions flow in order like Datadog's auto layout; **group widgets** as boxes with their title and background colour, collapsible |
| Top bar of a dashboard | Title; **template variables** as chips showing their current values (from the file's defaults and our `filters`, read-only); the **time picker** (Past 15 Minutes, 30 Minutes, 1 Hour, 3, 6, 12 Hours, 1 Day), every range live with ❚❚/▶, and Datadog's **auto interval** (10 s … 5 m, at most 300 points, average or sum) |
| Hovering a graph | A **crosshair shared by every timeseries** on the dashboard, with a tooltip of the values at that moment |
| Widget menu / click | **View in full screen**: the widget large, with its legend table (min, max, avg, value, sortable) |
| Clicking a line or a red anomaly marker | A **side panel** slides in from the right: that metric's details, its anomalies of the last 24 hours, and the **reason box** (per line, or "same reason for all lines") |

The separate region, panel and metric pages of `add-dashboard-view` are replaced by these.

### Widgets (the user chose to match the types)

| Datadog widget | Drawn as |
|---|---|
| `timeseries` | Lines, bars or areas as configured in the JSON (`display_type`), with its colours (palette), legend style and y-axis settings; **anomaly times as red bands**, past ("found in backfill") and BAU ones grey |
| `query_value` | A big number with its unit, precision and conditional colours from the JSON; a tiny trend line behind it |
| `toplist` | Horizontal bars, highest first, with values |
| `query_table` | A table of the latest value per tag combination, sortable |
| `sunburst` | A ring of the latest values per tag |
| `split_group` | One small graph per value of the split tag |
| `note` | Its text (Markdown) |
| `group` | A titled, coloured box holding its widgets |
| No metric (service map, logs, events, SLO) | A grey box: "not collected by this system" |

### Look

Datadog-like spacing, widget frames, title sizes, colour palettes (classic, cool, warm, purple, orange, gray) and axis
styles; **light or dark following the computer's setting**.

### Stays as built

Data plumbing, memory rules (charts only near the screen, live values only for those), Settings, reasons, BAU,
notifications.

## How we will know it works

| Check | Expected |
|---|---|
| Layout | The lab dashboard's widgets sit at the positions and sizes of its JSON; the Swagstore dashboard flows in order |
| Widgets | Query values are big numbers with their colours; the top list is bars; tables show latest values; the service map is a grey "not collected" box |
| Crosshair | Hovering one timeseries shows the same moment on every other |
| Full screen | A widget opens large with a min/max/avg/value legend |
| Side panel | Clicking a red band opens the metric's panel with its anomaly and reason box; a reason given there closes it as before |
| Side by side | Next to Datadog's own dashboard, the same widgets are in the same places with the same kind of drawing |
| Memory | 20 dashboards × 40 widgets stay under 200 MB (as measured before) |

## Capabilities

### Modified Capabilities
- `message-contracts`: a line's view names its widget (`widget_id`), for exact placement.
- `dashboard-view`: Datadog-like navigation, layout from the JSON, widget types, shared crosshair, full screen, side
  panel; replaces the region, panel and metric pages.

## Out of scope

| Not in this change | Why |
|---|---|
| Editing dashboards or template variables on the page | Read-only view of what is watched; JSON is edited in Settings |
| Datadog's logo, name, fonts, icons | Not ours to copy |
| Logs, APM, events, Metrics Explorer, monitors | The system collects dashboard metrics only |

## Impact

- `app/contracts`, `app/collector`: `View.widget_id`, filled from the JSON.
- `app/notifier`: serves each dashboard's JSON layout with the line catalogue (one answer per dashboard), so the page
  can place widgets and match their lines; per-widget latest values for tables, top lists and query values.
- `app/notifier/static`: the page rewritten (navigation, grid, widget renderers, crosshair, full screen, side panel).
- README.
