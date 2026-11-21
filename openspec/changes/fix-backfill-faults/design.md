# Design: fix-backfill-faults

## F1 Store: typed refresh, retried

`refresh_continuous_aggregate(regclass, "any", "any")` cannot type a bare `?`. The call casts both:
`?::timestamptz`. If it throws, `refreshLate()` hands the range back to `StoreStats.noteLate` (both ends) before
rethrowing, so the housekeeping loop's existing "retrying" path logs it and the next run (2 s later, or 10 s after an
error) refreshes the merged range. Nothing else changes.

## F2 Detector window: rebuild when stale marks pile up

`Window` keeps a sliding median with two heaps and lazy deletion: a value leaving the window is put in `delayed` and
dropped only when it reaches a heap top. Values sitting deep inside a heap stay for ever. Measured: 24 h of values
leaves 8,640 heap entries and 8,460 marks for 180 live values.

Fix: after each removal, if the heaps hold more than `2 × live + 16` entries, rebuild them from the ring buffer
(clear both heaps and `delayed`, insert the live values). Rebuild is O(w log w) with w = 180 and happens at most once
every ~w additions, so the amortised cost stays O(log w); memory stays below 2 × w + 16 entries. Medians are
unchanged because the live values are the same.

Why not another structure (indexable skip list, order-statistic tree): more code for the same result; the rebuild is
10 lines and keeps the tested median logic.

## F3 Detector size log

Once a minute (the engine's existing loop), log `lines watched, heap used / max MB`. Uses `Runtime` only.

## F4 Memory limit

`scripts/system.sh` starts each Java service with `-Xmx512m` (was 256m). Six services × 512 MB stays within a laptop;
F2 is the real fix, this is headroom for a 24-hour backfill burst.

## F5 Formula lines: name and rollup

**Name.** The page gets `query = "<formula>  where  <name> = <query>; …"` for formula lines. A new `lineName(line)`:
tags if the line has any (as today, like Datadog's legend), else the formula's alias, else the formula text, else the
metric. Aliases come from the dashboard JSON the page already loads: every `requests[].formulas[]` with an `alias`
goes into a map formula → alias (a formula with two different aliases is left without one). All places that used
`tagsText(...) || metric` call `lineName`.

**Rollup.** `DashService.method(query)` looked for `.rollup(sum` anywhere in the text, so a percentage made from
`.as_count()` counts was **summed** per graph step (two 10-second steps of 99% → 198%). For a formula line:
- SUM only when the formula is a plain sum or difference of queries (`query1 + query2`) and every query is a SUM;
- otherwise AVG (ratios, products, functions).
Single-query lines keep today's rule.

**Values compared.** The 0% the user saw cannot be checked here (no such panel locally; the lab traffic is off).
The panel's widget JSON from the user's machine, names removed, is compared with Datadog's own query for the same
15 minutes; tasks hold that step open until then.

## F6 Old settings warning

`scripts/check-config.sh` (called by `system.sh start`) compares a fixed list of keys the templates changed
(`raw-safety-limit`, `max-database-size`, `backfill-new-views`, `retention`, `settle-delay`) between
`config-template/*.yml` and `config-repo/*.yml` with `grep`/`sed` (first match per file), and prints each difference:
key, your value, template value, file. It never writes. Printing settings is safe: these files hold no secrets
(spec "No secrets in configuration files").
