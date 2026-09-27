#!/usr/bin/env bash
# Concurrency demo against the running app: N users hit the same seat at the same instant,
# then one user fires the same request N times with one Idempotency-Key.
# Expect exactly one 200 for the seat race, and exactly one booking for the duplicate burst.
#
# Run: bash scripts/race.sh [users=20] [seat=A01] [base=http://localhost:8080]
# More than 20 new users per hour from one machine needs the loadtest profile (per-IP sign-up limit).
set -euo pipefail

USERS=${1:-20}
SEAT=${2:-A01}
B=${3:-http://localhost:8080}
TOKENS=$(mktemp)
trap 'rm -f "$TOKENS"' EXIT

echo "Preparing $USERS users..."
seq 1 "$USERS" | xargs -P 20 -I{} curl -s -o /dev/null -X POST "$B/api/auth/register" \
  -H 'Content-Type: application/json' -d '{"username":"racer{}","password":"secret123"}'
for i in $(seq 1 "$USERS"); do   # sequential: parallel output could interleave tokens
  curl -s -X POST "$B/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"racer$i\",\"password\":\"secret123\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/'
  echo
done > "$TOKENS"

RUN=$(date +%s)
echo
echo "== $USERS users grab seat $SEAT at the same moment (expect 1 x 200, the rest 409)"
nl -w1 -s' ' "$TOKENS" | xargs -P "$USERS" -L1 sh -c \
  "curl -s -o /dev/null -w '%{http_code}\n' -X POST '$B/api/bookings' -H \"Authorization: Bearer \$1\" \
   -H \"Idempotency-Key: race-$RUN-\$0\" -H 'Content-Type: application/json' \
   -d '{\"eventId\":1,\"seatNo\":\"$SEAT\"}'" | sort | uniq -c

T=$(tail -1 "$TOKENS")
echo
echo "== racer$USERS sends the same request $USERS times at once, one Idempotency-Key"
echo "   (200 = HELD or DUPLICATE, 409 = IN_PROGRESS, 429 = rate limited)"
seq 1 "$USERS" | xargs -P "$USERS" -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$B/api/bookings" \
  -H "Authorization: Bearer $T" -H "Idempotency-Key: burst-$RUN" -H 'Content-Type: application/json' \
  -d '{"eventId":1,"seatNo":"J01"}' | sort | uniq -c
COUNT=$(curl -s "$B/api/bookings?eventId=1" -H "Authorization: Bearer $T" | grep -o "\"seatNo\":\"J01\"" | wc -l)
echo "   bookings racer$USERS has for J01: $COUNT (expect 1)"

echo
echo "Seat counts: $(curl -s "$B/api/events/1" | sed -E 's/.*"seats":(\{[^}]*\}).*/\1/')"
echo "Reset the event from the UI (admin) or: POST /api/admin/events/1/reset"
