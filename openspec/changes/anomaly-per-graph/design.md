# Design: anomaly-per-graph

**P1 Contract.** `View.split_tag = 8` (string). Addition only; baseline updated after the check.

**P2 Collector.** `DatadogDashboardReader` reads `split_config.split_dimensions[0].one_graph_per` of a split group and
keeps it on `Dashboard.Panel.splitTag` (new component, "" by default; recordings without it read as ""). `PollPlan`
copies it to `PollUnit.View.splitTag`; `AnswerTranslator` sets `View.split_tag`.

**P3 Detector.** `DetectorSettings.incidentScope` ("graph" default, or "service"; anything else is refused).
`IncidentBook.groupOf(line)` with scope graph:
`key = site | graph=<view id>/<widget id, else panel title>[/<split tag>=<value>]`, label = panel title, plus
" · tag=value" for a small graph; `upstream` = the line has an upstream tag (the title then reads "upstream …" only in
service scope, so not here); `byDashboard` false. A line's first place is `primaryView()` (views come in dashboard
order). Open incidents keep their keys: a member stays in its incident until it closes, only new joins use the new key.

**P4 Page.** `serviceName(inc)`: a `graph=` key gives the panel title and tag value. The Anomalies tree's third level is
the panel for graph-scoped anomalies; the Worst strip's first column is "Graph".
