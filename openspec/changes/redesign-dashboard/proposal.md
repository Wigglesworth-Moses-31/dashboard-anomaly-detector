# Proposal: redesign-dashboard

## Why

The user (21 Nov 2026, with photos of a real dashboard): "On the whole, I don't like the new design." What bothers
them, in their words and from the photos:

| # | Problem | What the photos show |
|---|---|---|
| 1 | **Graphs too small and crowded** | Split groups ("Success Rates", "Latencies", "Count") draw one graph per `api` at 180 px wide; the long titles (`api: /accounts/…/gateways/…/security/state`) run into each other and the axis numbers overlap |
| 2 | **Colours and contrast** | Whole graphs shaded red, red edges on whole sections, thin blue lines on a dark page |
| 3 | **Navigation** | Region list, dashboard list, dashboard, anomalies and settings are separate pages; moving between them takes several clicks |
| 4 | **Anomalies feel separate** | To see what is wrong you leave the graphs for the Anomalies page, and there is no "worst first" list |
| 5 | **No way to show only one line** | Clicking a line in a legend does nothing |
| 6 | **A shutdown leaves a hole** | After the laptop is off for some hours, those hours stay empty; only new dashboards get a backfill |

## What Changes

### A. Bigger, cleaner graphs
| What | How |
|---|---|
| Split groups | Each small graph at least **320 px wide and 160 px high**; as many per row as fit, never squeezed |
| Long titles | One line, shortened in the middle (`/accounts/…/security/state`), full title on hover |
| Axes | At most 4 value ticks, short numbers (`99.8%`, `1.2k`, `350 ms`), never overlapping |
| Narrow widgets | A widget that would be under 360 px on screen takes the whole row of its group; the JSON order stays |
| Density | A **Comfortable / Compact** switch in the top bar (Comfortable by default), remembered |
| **Zoom follows the browser** | The user (21 Nov): "when I zoom in graphs should become bigger, when I zoom out it should become smaller". Today every widget is a fraction of the window (Datadog's 12 columns), so browser zoom only grows the text and the graphs keep their share of the screen. Instead every graph has a size in page units (for example 320 × 160); zooming in (Cmd/Ctrl +) makes each graph bigger and fewer fit per row, zooming out makes them smaller and more fit. Charts redraw at the new size at once, and the dashboard keeps the widget you were looking at in view |
| Zoom in the page too | **−  100%  +** in the top bar does the same for the graphs only (75% to 200%), remembered per browser, for screens where browser zoom is inconvenient |

### B. Calmer colours, clearer signals
| What | How |
|---|---|
| Anomaly shading | Only the anomaly's own time span gets a light band; the rest of the graph stays clean |
| Widget marking | A small coloured dot by the title (red = open, amber = needs a reason, grey = past) instead of red edges on whole sections |
| Lines | 1.5 px lines, a colour-blind-safe palette, distinct colours for up to 10 lines |
| Contrast | Text and axes meet normal readability contrast in both light and dark |

### C. One top bar for navigation
| What | How |
|---|---|
| Top bar, always visible | **Region** switch (US · EU) → **Dashboard** picker (type to search; each shows its open-anomaly count) → time range → live |
| Breadcrumb | Region › Dashboard › Group › Widget; the browser's Back works at every step |
| Left bar | Only **Dashboards**, **Anomalies**, **Settings** |

### D. Anomalies on the dashboard: "Worst anomalies" strip
| What | How |
|---|---|
| Where | At the top of every dashboard, folded to the **top 5**, unfoldable to all |
| Ranked by | State first (open, then needs a reason, then closed, then past), then **size × time**: how far the worst line is from normal (for example 3.2 × normal, or −45 points) times how many minutes it has lasted |
| Each row | Rank, service, line, signal (spike, drop, silent…), size, how long, state |
| Click | Scrolls to the widget, shows only that line in it (E), opens its side panel for the reason |
| Groups | A group header shows its anomaly count, also when folded |

The Anomalies page stays for all dashboards at once, with the same ranking as a sort option.

### E. Show only one line
Clicking a line in a legend (or the side panel's table) shows **only that line**; clicking it again shows all.
Ctrl/Cmd-click adds or removes one line. Works in a widget, in full screen and in the side panel, as in Datadog.

### F. Automatic catch-up after a shutdown
| What | How |
|---|---|
| When | Every start, also after a crash or power cut. Nothing to tell the system before shutting down |
| How | The Collector asks the Store for the newest saved time of each dashboard view. A gap longer than the normal poll window is filled from that time to now, **at most 24 hours**, with the existing backfill (one Datadog call per 4-hour window, oldest first) |
| What the Detector does | Values found in the catch-up are judged as the past, like a backfill ("found in backfill", no reason needed) |
| Shown | The top bar says "Catching up 3 h 20 min · 40%" until done |

### G. Feedback of 22 Nov 2026
The user: "I don't want a separate anomaly graph on the right side, I need to sync it with the existing graph itself,
and enlarge a specific metric is not available for every metrics panel". Opening a line now highlights it on the
graphs already on screen (bold line, faded others, labelled band, dot at the peak); the right-hand panel keeps only
details and the reason box, and the page makes room for it. Every graph, including each small graph of a split group,
gets a visible enlarge button.

## Capabilities

### Modified Capabilities
- `dashboard-view`: layout sizes, colours, top bar navigation, Worst-anomalies strip, show-only-one-line.
- `metric-collection`: catch-up of the gap since the last saved value, at most 24 hours, at every start.
- `metric-storage`: answers the newest saved time per dashboard view.
- `message-contracts`: one new Store call for that answer.

## Impact

`app/notifier` static page (most of the work), `app/collector` (catch-up planning), `app/store` and
`app/contracts` (newest time per view). No data wipe. The Datadog rate use for a catch-up is the same as a
backfill: 6 calls per view for 24 hours.
