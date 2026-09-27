#!/usr/bin/env bash
# Stops Kafka, Redis and MongoDB to free RAM. Data is kept.
set -uo pipefail

TOOLS="$HOME/tools"
export LD_LIBRARY_PATH="$TOOLS/redis/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if ! command -v java > /dev/null && [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  set +u; source "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
fi

"$TOOLS/kafka/bin/kafka-server-stop.sh" 2> /dev/null && echo "kafka: stopping"

MAILPIT_PID=$(pgrep -f "$TOOLS/mailpit/mailpit" || true)
[ -n "$MAILPIT_PID" ] && kill $MAILPIT_PID && echo "mailpit: stopped"

if systemctl is-active --quiet redis-server 2> /dev/null; then
  sudo systemctl stop redis-server && echo "redis: stopped"
elif [ -x "$TOOLS/redis/usr/bin/redis-cli" ]; then
  "$TOOLS/redis/usr/bin/redis-cli" shutdown > /dev/null 2>&1 && echo "redis: stopped"
fi

if systemctl is-active --quiet mongod 2> /dev/null; then
  sudo systemctl stop mongod && echo "mongodb: stopped"
elif [ -x "$TOOLS/mongosh/bin/mongosh" ]; then
  "$TOOLS/mongosh/bin/mongosh" --quiet --eval 'db.getSiblingDB("admin").shutdownServer()' > /dev/null 2>&1
  echo "mongodb: stopped"
fi
