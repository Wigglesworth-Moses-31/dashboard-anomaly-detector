"""Tests every metrics query in a dashboard JSON against Datadog (read-only).

  python3 -I tools/check-queries.py path/to/dashboard.json [env-value]
Template variables are replaced: $env becomes env:<env-value> (default prod), the others are dropped.
"""
import json, re, subprocess, sys, time, pathlib

HERE = pathlib.Path(__file__).resolve().parent.parent
env = dict(l.strip().split("=", 1) for l in (HERE / ".env").read_text().splitlines() if "=" in l)
dashboard = json.load(open(sys.argv[1]))
env_value = sys.argv[2] if len(sys.argv) > 2 else "prod"


def queries(node):
    if isinstance(node, dict):
        if node.get("data_source") == "metrics" and "query" in node:
            yield node["query"]
        for value in node.values():
            yield from queries(value)
    elif isinstance(node, list):
        for value in node:
            yield from queries(value)


bad = 0
unique = sorted(set(queries(dashboard)))
for query in unique:
    test = query.replace("$env", f"env:{env_value}")
    test = re.sub(r"\$\w+\s*,?\s*", "", test)      # drop the other template variables
    test = re.sub(r",\s*}", "}", test).replace("{,", "{")
    now = int(time.time())
    out = subprocess.run(["curl", "-sS", "--max-time", "30", "-G", f"https://api.{env['DD_SITE']}/api/v1/query",
        "--data-urlencode", f"query={test}", "--data-urlencode", f"from={now - 900}", "--data-urlencode", f"to={now}",
        "-H", f"DD-API-KEY: {env['DD_API_KEY']}", "-H", f"DD-APPLICATION-KEY: {env['DD_APP_KEY']}"],
        capture_output=True, text=True).stdout
    try:
        data = json.loads(out)
    except ValueError:
        data = {"status": "error", "error": out[:120]}
    count = len(data.get("series", []))
    ok = data.get("status") == "ok" and count > 0
    bad += not ok
    print("ok " if ok else "BAD", f"{count:3} series ", test[:105], "" if ok else "-> " + str(data.get("error", "no data"))[:140])
    time.sleep(0.2)
print(f"{len(unique) - bad} of {len(unique)} queries returned data")
