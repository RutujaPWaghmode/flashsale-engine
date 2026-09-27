#!/usr/bin/env bash
# Proves the naive booking oversells and the atomic/Redis ones do not: many users grab the same seat at once.
#
# Before running (same setup as benchmark.sh):
#   1. bash scripts/start-local.sh
#   2. ./mvnw spring-boot:run -Dspring-boot.run.profiles=loadtest
#
# Run:    bash scripts/oversell.sh [users=100] [rounds=10]
# Output: printed, and saved to benchmarks/oversell-<timestamp>.txt
set -euo pipefail

USERS=${1:-100}
ROUNDS=${2:-10}
BASE=${BASE_URL:-http://localhost:8080}
K6="$HOME/tools/k6/k6"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/benchmarks/oversell-$(date +%Y%m%d-%H%M%S).txt"
mkdir -p "$ROOT/benchmarks"

ADMIN_PASSWORD=$(grep '^ADMIN_PASSWORD=' "$ROOT/.env" | cut -d= -f2-)
[ -n "$ADMIN_PASSWORD" ] || { echo "ADMIN_PASSWORD missing in .env" >&2; exit 1; }
curl -sf "$BASE/actuator/health" | grep -q '"UP"' || { echo "App is not running at $BASE" >&2; exit 1; }

for MODE in naive atomic redis; do
  "$K6" run --quiet -e MODE="$MODE" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" -e BASE_URL="$BASE" \
    -e USERS="$USERS" -e ROUNDS="$ROUNDS" "$ROOT/load/oversell.js" 2>&1 | grep -v '^time=' || {
      echo "k6 failed for $MODE. Is the app running with -Dspring-boot.run.profiles=loadtest ?" >&2; exit 1; }
  echo
done | tee "$OUT"

echo "Saved: $OUT"
