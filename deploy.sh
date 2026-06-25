#!/usr/bin/env bash
# Sima backend — Kamatera serverga bitta buyruq bilan deploy.
# Ishlatilishi: ./deploy.sh
# Talab: ~/.ssh/config'da "sima" host yozilgan bo'lishi kerak.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

HOST="sima"
REMOTE_PATH="/opt/sima/app.jar"
SERVICE="sima-backend"

echo "→ 1/4  Build (mvn package, testlarsiz)..."
mvn -q -DskipTests package

JAR=$(ls -1 target/*.jar 2>/dev/null | grep -v "original" | head -n 1)
if [ -z "$JAR" ]; then
  echo "✗ JAR topilmadi (target/*.jar)"
  exit 1
fi
SIZE=$(du -h "$JAR" | cut -f1)
echo "  ✓ JAR: $JAR ($SIZE)"

echo "→ 2/4  Yuklash ($HOST:$REMOTE_PATH)..."
scp "$JAR" "$HOST:$REMOTE_PATH"

echo "→ 3/4  Service qayta ishga tushirish..."
ssh "$HOST" "systemctl restart $SERVICE"

echo "→ 4/4  Startup'ni kutish (30s)..."
sleep 30

echo ""
echo "─── Health check ───"
HEALTH=$(ssh "$HOST" "curl -fsS http://localhost:8080/api/health" 2>/dev/null || echo "FAIL")
if [ "$HEALTH" = "FAIL" ]; then
  echo "✗ Health check ishlamadi. Log'ni tekshiring:"
  ssh "$HOST" "journalctl -u $SERVICE --no-pager -n 30"
  exit 1
fi
echo "✓ $HEALTH"

echo ""
echo "─── Oxirgi 10 log qatori ───"
ssh "$HOST" "journalctl -u $SERVICE --no-pager -n 10 | grep -v '^--'"

echo ""
echo "✓ Deploy tugadi."
