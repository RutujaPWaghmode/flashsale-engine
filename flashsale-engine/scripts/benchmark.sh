#!/usr/bin/env bash
# Runs the k6 flash-sale load test in all three modes and writes a results table.
#
# Before running:
#   1. Close heavy apps (browsers, extra VS Code windows): numbers are only honest on a quiet machine.
#   2. bash scripts/start-local.sh
#   3. Start the app with the loadtest profile (lifts the per-IP sign-up limit for the k6 users):
#        ./mvnw spring-boot:run -Dspring-boot.run.profiles=loadtest
#
# Run:   bash scripts/benchmark.sh [rate=1000] [hold=45s] [users=300]
# Tip:   run it at 2 or 3 rates (e.g. 1000, 3000, 6000) and keep the one where the modes separate.
# Output: benchmarks/results-<timestamp>.md (plus the raw k6 JSON next to it)
set -euo pipefail

RATE=${1:-1000}
HOLD=${2:-45s}
USERS=${3:-300}
RAMP=15s
BASE=${BASE_URL:-http://localhost:8080}
K6="$HOME/tools/k6/k6"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/benchmarks"
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"

ADMIN_PASSWORD=$(grep '^ADMIN_PASSWORD=' "$ROOT/.env" | cut -d= -f2-)
[ -n "$ADMIN_PASSWORD" ] || { echo "ADMIN_PASSWORD missing in .env" >&2; exit 1; }
curl -sf "$BASE/actuator/health" | grep -q '"UP"' || { echo "App is not running at $BASE" >&2; exit 1; }

AVAILABLE_RAM=$(free -h | awk '/Mem:/{print $7}')
echo "Free RAM right now: $AVAILABLE_RAM (close other apps if this is under ~4 GB)"
echo

for MODE in atomic redis naive; do
  echo "=== $MODE: ramp to $RATE req/s over $RAMP, hold $HOLD, $USERS users"
  set +e
  "$K6" run --quiet --summary-trend-stats "avg,med,p(95),p(99),max" \
    --summary-export "$OUT/$STAMP-$MODE.json" \
    -e MODE="$MODE" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" -e BASE_URL="$BASE" \
    -e RATE="$RATE" -e RAMP="$RAMP" -e HOLD="$HOLD" -e USERS="$USERS" \
    "$ROOT/load/booking-load.js" > "$OUT/$STAMP-$MODE.log" 2>&1
  CODE=$?
  set -e
  if [ ! -s "$OUT/$STAMP-$MODE.json" ]; then
    echo "k6 failed (exit $CODE); see $OUT/$STAMP-$MODE.log" >&2
    grep -iE 'error|login failed|could log in' "$OUT/$STAMP-$MODE.log" | head -5 >&2
    echo "Hint: is the app running with -Dspring-boot.run.profiles=loadtest ?" >&2
    exit 1
  fi
  [ $CODE -eq 99 ] && echo "   (a k6 threshold was crossed; numbers are still recorded)"
  sleep 10   # let the app settle (GC, Kafka, sweeper) before the next mode
done

python3 - "$OUT" "$STAMP" "$RATE" "$RAMP" "$HOLD" "$USERS" "$AVAILABLE_RAM" <<'PY'
import json, platform, subprocess, sys, os
out, stamp, rate, ramp, hold, users, ram = sys.argv[1:]

def sh(cmd):
    try: return subprocess.check_output(cmd, shell=True, text=True).strip()
    except Exception: return "?"

cpu = sh("lscpu | sed -n 's/^Model name:[ ]*//p'")
cores = sh("nproc")
total_ram = sh("free -h | awk '/Mem:/{print $2}'")
java = sh("bash -c 'source ~/.sdkman/bin/sdkman-init.sh >/dev/null 2>&1; java -version 2>&1 | head -1'")

rows = []
for mode, label in [("naive", "Naive read-then-write"), ("atomic", "MongoDB atomic update"), ("redis", "Redis Lua hold")]:
    m = json.load(open(f"{out}/{stamp}-{mode}.json"))["metrics"]
    dur = m.get("http_req_duration{expected_response:true}") or m["http_req_duration"]
    val = lambda k: (m.get(k) or {}).get("count", 0)
    rows.append((label, m["http_reqs"]["rate"], dur["med"], dur["p(95)"], dur["p(99)"],
                 int(val("seats_won")), int(val("dropped_iterations")),
                 m["http_req_failed"].get("value", m["http_req_failed"].get("rate", 0))))

lines = [
    f"# Benchmark {stamp}", "",
    f"- Machine: {cpu}, {cores} threads, {total_ram} RAM ({ram} free at start), {platform.system()} {platform.release()}",
    f"- Runtime: {java}; MongoDB, Redis, Kafka and the app on the same machine",
    f"- Load: k6 ramping-arrival-rate to {rate} req/s over {ramp}, held {hold}; {users} users; random seat of 100; POST only",
    "",
    "| Mode | Throughput (req/s) | Median | p95 | p99 | Seats won | Dropped | Failed |",
    "|------|-------------------|--------|-----|-----|-----------|---------|--------|",
]
for label, rps, med, p95, p99, won, dropped, failed in rows:
    lines.append(f"| {label} | {rps:,.0f} | {med:.1f} ms | {p95:.1f} ms | {p99:.1f} ms | {won} | {dropped:,} | {failed*100:.2f}% |")
lines += ["",
          "- Seats won above 100 means a seat was sold more than once.",
          "- Dropped = requests k6 could not even start because the app was too slow to keep up (0 means it kept up).",
          "- Throughput is achieved requests per second, averaged over the whole run including the ramp.",
          "- If every mode shows 0 dropped and similar latency, the rate was too low to separate them: raise it."]
text = "\n".join(lines) + "\n"
open(f"{out}/results-{stamp}.md", "w").write(text)
print("\n" + text)
print(f"Saved: {out}/results-{stamp}.md")
PY
