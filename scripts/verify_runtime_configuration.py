"""Runs every scenario of the runtime-configuration spec against the running system.

    scripts/system.sh start
    python3 -I scripts/verify_runtime_configuration.py

Spec: openspec/changes/add-system-architecture/specs/runtime-configuration/spec.md
It commits test changes to config-repo/ and restores the original settings at the end (also on failure).
Secret values are only compared, never printed. Exit code 0 means every scenario passed.
"""
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = ROOT / "config-repo"
COLLECTOR_YML = REPO / "collector.yml"
RUN = ROOT / "run"
LIMIT_S = 60                      # the spec's "within 60 seconds"
TEST_DASHBOARD = "verify-dash-001"
results = []                      # (scenario, outcome, detail)


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def collector_settings():
    return get_json("http://localhost:8101/actuator/settings")["anomaly.collector"]


def collector_health():
    return get_json("http://localhost:8101/actuator/health")


def collector_pid():
    return (RUN / "collector.pid").read_text().strip()


def commit(message):
    """Commits and returns the time just before the commit, so measurements include the hook's broadcast."""
    started = time.time()
    subprocess.run(["git", "-C", str(REPO), "-c", "user.name=anomaly-lab", "-c", "user.email=anomaly-lab@localhost",
                    "commit", "-q", "-am", message], check=True, capture_output=True)
    return started


def wait_until(condition, start=None):
    """Polls twice a second; returns seconds since `start` (default: now), or None after the spec's limit."""
    start = start or time.time()
    while time.time() - start < LIMIT_S:
        try:
            if condition():
                return round(time.time() - start, 1)
        except Exception:
            pass
        time.sleep(0.5)
    return None


def record(name, ok, detail):
    results.append((name, "Pass" if ok else "FAIL", detail))


def secrets():
    env = dict(line.strip().split("=", 1) for line in (ROOT / ".env").read_text().splitlines() if "=" in line)
    return [v.encode() for k, v in env.items() if ("KEY" in k or "PASSWORD" in k) and v]


def set_poll_interval(value):
    text = COLLECTOR_YML.read_text()
    COLLECTOR_YML.write_text(re.sub(r"poll-interval: [^#\n]*?(\s+#)", f"poll-interval: {value}\\1", text, count=1))


def run_scenarios():
    # Scenario: each service sees only its own settings plus shared ones
    sources = get_json("http://localhost:8888/reporter/default")["propertySources"]
    leaked = [k for s in sources for k in s["source"] if k.startswith("anomaly.collector")]
    groups = sorted(get_json("http://localhost:8105/actuator/settings"))
    record("Each service sees only its own settings plus shared ones",
           not leaked and groups == ["anomaly.reporter", "anomaly.shared"],
           f"Reporter's groups: {', '.join(groups)}; Collector keys received: {len(leaked)}")

    # Scenario: add a dashboard to watch
    pid = collector_pid()
    text = COLLECTOR_YML.read_text()
    COLLECTOR_YML.write_text(text.replace("      - jrp-8af-7xd", f"      - jrp-8af-7xd\n      - {TEST_DASHBOARD}", 1))
    started = commit("Verification: watch one more dashboard")
    took = wait_until(lambda: TEST_DASHBOARD in collector_settings()["values"]["dashboards"], started)
    record("Add a dashboard to watch", took is not None and collector_pid() == pid,
           f"new id visible after {took} s, same process: {collector_pid() == pid}")

    # Scenario: poll interval change applies within 60 s, without a restart
    set_poll_interval("30s")
    started = commit("Verification: poll every 30 seconds")
    took = wait_until(lambda: collector_settings()["values"]["pollInterval"] == "PT30S", started)
    record("Poll interval change (within 60 s, no restart)", took is not None and collector_pid() == pid,
           f"30 s visible after {took} s, same process: {collector_pid() == pid}")

    # Scenario: typo in a setting is rejected safely
    log_before = (RUN / "collector.log").read_text().count("change rejected")
    set_poll_interval("-5 seconds")
    started = commit("Verification: bad value")
    took = wait_until(lambda: collector_health()["status"] == "DEGRADED", started)
    health = collector_health()
    rejected = health["components"]["settings"]["details"]["anomaly.collector"].get("rejected", [])
    in_use = collector_settings()["values"]["pollInterval"]
    logged = (RUN / "collector.log").read_text().count("change rejected") > log_before
    record("Typo in a setting is rejected safely",
           took is not None and in_use == "PT30S" and any("poll-interval" in p for p in rejected) and logged,
           f"health DEGRADED after {took} s; still using {in_use}; problem: {rejected[0] if rejected else '-'}; logged: {logged}")


def restore(original_text):
    started = time.time()
    if COLLECTOR_YML.read_text() != original_text:
        COLLECTOR_YML.write_text(original_text)
        started = commit("Verification: restore original settings")
    took = wait_until(lambda: collector_health()["status"] == "UP"
                      and collector_settings()["values"]["pollInterval"] == "PT15S"
                      and TEST_DASHBOARD not in collector_settings()["values"]["dashboards"], started)
    record("Fixing the setting returns health to UP", took is not None,
           f"UP with 15 s and the original dashboards after {took} s")


def check_secrets():
    values = secrets()
    tracked = [p for p in REPO.rglob("*") if p.is_file() and ".git" not in p.parts]
    in_files = [str(p.name) for p in tracked if any(v in p.read_bytes() for v in values)]
    history = subprocess.run(["git", "-C", str(REPO), "log", "-p", "--all"], capture_output=True).stdout
    in_history = any(v in history for v in values)
    record("No secrets in configuration files", not in_files and not in_history,
           f"{len(tracked)} files and the full Git history scanned: {'found' if in_files or in_history else 'none found'}")

    log = (RUN / "collector.log").read_text()
    line_ok = "Datadog keys from the environment: API key present, application key present" in log
    logs = list(RUN.glob("*.log"))
    leaked = [p.name for p in logs if any(v in p.read_bytes() for v in values)]
    record("Startup log shows keys present, never values", line_ok and not leaked,
           f"presence line found: {line_ok}; {len(logs)} logs scanned for secret values: {'found in ' + str(leaked) if leaked else 'none found'}")


def main():
    for port in (8888, 8101, 8102, 8103, 8104, 8105):
        get_json(f"http://localhost:{port}/actuator/health")   # fails fast if something is down
    original = COLLECTOR_YML.read_text()
    try:
        run_scenarios()
    finally:
        restore(original)
    check_secrets()

    print("| Scenario | Result | Detail |\n|---|---|---|")
    for name, outcome, detail in results:
        print(f"| {name} | **{outcome}** | {detail} |")
    return 0 if all(outcome == "Pass" for _, outcome, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
