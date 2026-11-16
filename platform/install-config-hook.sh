#!/usr/bin/env bash
# Installs a post-commit hook in config-repo: after every commit it asks the Config Service to broadcast
# a refresh over Kafka (Spring Cloud Bus), so every running service reloads its settings.
set -euo pipefail
HOOK="$(cd "$(dirname "$0")/../config-repo" && pwd)/.git/hooks/post-commit"
cat > "$HOOK" <<'HOOKEOF'
#!/usr/bin/env bash
# Tell the Config Service that settings changed (it broadcasts to all services over Kafka).
if curl -fsS -m 10 -X POST http://localhost:8888/actuator/busrefresh >/dev/null 2>&1; then
  echo "settings change broadcast to all services"
else
  echo "Config Service not reachable; services will pick up the change on their next start" >&2
fi
HOOKEOF
chmod +x "$HOOK"
echo "installed $HOOK"
