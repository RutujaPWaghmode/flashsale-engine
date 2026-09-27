#!/usr/bin/env bash
# One-time install of everything that does not need sudo, under ~/tools:
# Kafka (KRaft), k6, MongoDB + mongosh, Redis, Mailpit.
# Run: bash scripts/setup-user-tools.sh
set -euo pipefail

# Kafka needs Java; load SDKMAN's Java in non-interactive shells.
if ! command -v java > /dev/null && [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
  set +u; source "$HOME/.sdkman/bin/sdkman-init.sh"; set -u
fi

KAFKA_VERSION=4.3.1
K6_VERSION=2.3.0
TOOLS="$HOME/tools"
mkdir -p "$TOOLS"

if [ ! -d "$TOOLS/kafka" ]; then
  echo "==> Downloading Kafka $KAFKA_VERSION"
  curl -fsSL "https://downloads.apache.org/kafka/$KAFKA_VERSION/kafka_2.13-$KAFKA_VERSION.tgz" | tar -xz -C "$TOOLS"
  mv "$TOOLS/kafka_2.13-$KAFKA_VERSION" "$TOOLS/kafka"
fi

echo "==> Configuring Kafka (data in ~/tools/kafka-data, 24h retention)"
CONF="$TOOLS/kafka/config/server.properties"
sed -i "s#^log.dirs=.*#log.dirs=$TOOLS/kafka-data#" "$CONF"
sed -i 's#^log.retention.hours=.*#log.retention.hours=24#' "$CONF"

if [ ! -f "$TOOLS/kafka-data/meta.properties" ]; then
  echo "==> Formatting Kafka storage"
  CLUSTER_ID="$("$TOOLS/kafka/bin/kafka-storage.sh" random-uuid)"
  "$TOOLS/kafka/bin/kafka-storage.sh" format --standalone -t "$CLUSTER_ID" -c "$CONF"
fi

if [ ! -x "$TOOLS/k6/k6" ]; then
  echo "==> Downloading k6 $K6_VERSION"
  mkdir -p "$TOOLS/k6"
  curl -fsSL "https://github.com/grafana/k6/releases/download/v$K6_VERSION/k6-v$K6_VERSION-linux-amd64.tar.gz" | \
    tar -xz -C "$TOOLS/k6" --strip-components=1
fi

# MongoDB and Redis without sudo. Skipped if scripts/install-dev-tools.sh already installed them system-wide.
MONGO_VERSION=8.0.32
MONGOSH_VERSION=2.12.0

if ! command -v mongod > /dev/null && [ ! -x "$TOOLS/mongodb/bin/mongod" ]; then
  echo "==> Downloading MongoDB $MONGO_VERSION"
  curl -fsSL --retry 5 --retry-all-errors -o "$TOOLS/mongodb.tgz" \
    "https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-$MONGO_VERSION.tgz"
  tar -xzf "$TOOLS/mongodb.tgz" -C "$TOOLS" && rm "$TOOLS/mongodb.tgz"
  mv "$TOOLS/mongodb-linux-x86_64-ubuntu2204-$MONGO_VERSION" "$TOOLS/mongodb"
fi

if ! command -v mongosh > /dev/null && [ ! -x "$TOOLS/mongosh/bin/mongosh" ]; then
  echo "==> Downloading mongosh $MONGOSH_VERSION"
  curl -fsSL --retry 5 --retry-all-errors -o "$TOOLS/mongosh.tgz" \
    "https://downloads.mongodb.com/compass/mongosh-$MONGOSH_VERSION-linux-x64.tgz"
  tar -xzf "$TOOLS/mongosh.tgz" -C "$TOOLS" && rm "$TOOLS/mongosh.tgz"
  mv "$TOOLS/mongosh-$MONGOSH_VERSION-linux-x64" "$TOOLS/mongosh"
fi

if ! command -v redis-server > /dev/null && [ ! -x "$TOOLS/redis/usr/bin/redis-server" ]; then
  echo "==> Extracting Redis from the Ubuntu packages (no install)"
  PKGS=$(mktemp -d)
  (cd "$PKGS" && apt-get download redis-server redis-tools libjemalloc2 liblzf1 lua-cjson lua-bitop liblua5.1-0 > /dev/null)
  mkdir -p "$TOOLS/redis"
  for deb in "$PKGS"/*.deb; do dpkg -x "$deb" "$TOOLS/redis"; done
  rm -rf "$PKGS"
fi

if [ ! -x "$TOOLS/mailpit/mailpit" ]; then
  echo "==> Downloading Mailpit (local fake inbox for emails)"
  mkdir -p "$TOOLS/mailpit"
  curl -fsSL --retry 5 --retry-all-errors \
    "https://github.com/axllent/mailpit/releases/latest/download/mailpit-linux-amd64.tar.gz" | tar -xz -C "$TOOLS/mailpit"
fi

mkdir -p "$TOOLS/data/mongodb" "$TOOLS/data/redis"
echo "Done. Everything is under $TOOLS"
