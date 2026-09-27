#!/usr/bin/env bash
# Starts MongoDB, Redis and Kafka for local development, then creates the Kafka topics.
# Uses the system services if scripts/install-dev-tools.sh was run, otherwise the copies
# under ~/tools from scripts/setup-user-tools.sh (no sudo needed).
# Run: bash scripts/start-local.sh      (then: ./mvnw spring-boot:run)
set -euo pipefail

TOOLS="$HOME/tools"
KAFKA="$TOOLS/kafka"
export LD_LIBRARY_PATH="$TOOLS/redis/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
if ! command -v java > /dev/null && [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  set +u; source "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
fi

MONGOSH=$(command -v mongosh || echo "$TOOLS/mongosh/bin/mongosh")
REDIS_CLI=$(command -v redis-cli || echo "$TOOLS/redis/usr/bin/redis-cli")

port_open() { (echo > "/dev/tcp/localhost/$1") 2> /dev/null; }
wait_port() { for _ in $(seq 1 30); do port_open "$1" && return 0; sleep 1; done; echo "port $1 did not open" >&2; return 1; }

# MongoDB (single-node replica set rs0)
if port_open 27017; then
  echo "mongodb: already running"
elif command -v mongod > /dev/null && systemctl list-unit-files mongod.service > /dev/null 2>&1; then
  echo "mongodb: starting system service"; sudo systemctl start mongod
else
  echo "mongodb: starting ~/tools copy"
  "$TOOLS/mongodb/bin/mongod" --dbpath "$TOOLS/data/mongodb" --replSet rs0 --bind_ip 127.0.0.1 --port 27017 \
    --wiredTigerCacheSizeGB 0.5 --fork --logpath "$TOOLS/data/mongod.log" > /dev/null
fi
wait_port 27017
"$MONGOSH" --quiet --eval '
  try { rs.status(); }
  catch (e) { rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "localhost:27017" }] }); print("mongodb: replica set initiated"); }'

# Redis
if port_open 6379; then
  echo "redis: already running"
elif command -v redis-server > /dev/null && systemctl list-unit-files redis-server.service > /dev/null 2>&1; then
  echo "redis: starting system service"; sudo systemctl start redis-server
else
  echo "redis: starting ~/tools copy"
  "$TOOLS/redis/usr/bin/redis-server" --port 6379 --bind 127.0.0.1 --daemonize yes --dir "$TOOLS/data/redis" \
    --appendonly yes --maxmemory 256mb --maxmemory-policy volatile-ttl --logfile "$TOOLS/data/redis.log"
fi
wait_port 6379

# Mailpit: catches every email the app sends (SMTP 1025, inbox at http://localhost:8025)
if port_open 1025; then
  echo "mailpit: already running"
elif [ -x "$TOOLS/mailpit/mailpit" ]; then
  echo "mailpit: starting"
  nohup "$TOOLS/mailpit/mailpit" --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025 \
    --database "$TOOLS/data/mailpit.db" > "$TOOLS/data/mailpit.log" 2>&1 &
  wait_port 1025
else
  echo "mailpit: not installed (run scripts/setup-user-tools.sh); emails will be retried then dead-lettered"
fi

# Kafka
if port_open 9092; then
  echo "kafka: already running"
else
  echo "kafka: starting"
  KAFKA_HEAP_OPTS="-Xms512m -Xmx512m" "$KAFKA/bin/kafka-server-start.sh" -daemon "$KAFKA/config/server.properties"
  wait_port 9092
fi

for topic in payment-requested payment-succeeded payment-failed notifications booking-dlq; do
  "$KAFKA/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$topic" --partitions 3 --replication-factor 1 > /dev/null
done

# The replica set needs a few seconds to elect itself primary.
for _ in $(seq 1 20); do
  [ "$("$MONGOSH" --quiet --eval 'db.hello().isWritablePrimary')" = "true" ] && break; sleep 1
done

echo
echo "MongoDB primary: $("$MONGOSH" --quiet --eval 'db.hello().isWritablePrimary')"
echo "Redis: $("$REDIS_CLI" ping)"
echo "Kafka topics: $("$KAFKA/bin/kafka-topics.sh" --bootstrap-server localhost:9092 --list | tr '\n' ' ')"
port_open 8025 && echo "Emails: http://localhost:8025"
