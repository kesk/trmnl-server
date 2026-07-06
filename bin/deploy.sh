#!/usr/bin/env bash
# Builds an uberjar and ships it to dashboard-pi, then restarts the systemd service.
set -euo pipefail

HOST=dashboard-pi
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_ROOT/target/trmnl-server.jar"

cd "$REPO_ROOT"
clojure -T:build uber

scp "$JAR" "$HOST":~/trmnl-server/trmnl-server.jar

ssh "$HOST" 'sudo systemctl restart trmnl-server'

echo "Waiting for server to come back up..."
for _ in $(seq 1 20); do
  if ssh "$HOST" 'curl -sf -o /dev/null http://localhost:8080/api/display'; then
    echo "Deployed and healthy."
    exit 0
  fi
  sleep 2
done

echo "Server did not become healthy within 40s of restart" >&2
exit 1
