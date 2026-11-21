# Verification: group-anomalies

21 Nov 2026. Anomalies are listed as region › dashboard › service › anomaly.

| Check | Result |
|---|---|
| Build | The Notifier's tests pass (`mvnw -pl notifier package`) |
| Real data on this machine | The 19 waiting anomalies (from 15 Nov) appear as 2 regions › 4 dashboards › 19 services › 19 anomalies; EU › Lab: API platform shows "5 open · 5 total"; each anomaly lists its lines (widget · tags, signal, direction, change), each linking to its side panel |
| Sorting | Services worst first, named services before "(no service tag)", anomalies newest first; lines by widget, then tags |
| Titles | The region prefix the Detector puts in titles ("EU · …") is dropped inside the tree, which already shows the region |
| Filters | State chips (open and waiting on at first; all four shown in the dark screenshot); search over service, dashboard and line |
| Counter | The left counter now opens the page with open and waiting anomalies |
| Light and dark | Both screenshots |

| Note | Why |
|---|---|
| The counter counts metrics (62 open · 18 waiting), the tree counts anomalies (19) | One anomaly holds many lines; the counter keeps its meaning ("metrics that need attention") |
