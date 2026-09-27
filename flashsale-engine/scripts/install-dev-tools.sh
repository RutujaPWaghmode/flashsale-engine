#!/usr/bin/env bash
# One-time install of the tools that need sudo: MongoDB 8 (single-node replica set) and Redis.
# Also caps the ZFS cache at 2 GB until the next reboot, to free RAM.
# Run: bash scripts/install-dev-tools.sh
set -euo pipefail

echo "==> Capping ZFS cache at 2 GB (resets on reboot)"
if [ -w /sys/module/zfs/parameters/zfs_arc_max ] || [ -e /sys/module/zfs/parameters/zfs_arc_max ]; then
  echo 2147483648 | sudo tee /sys/module/zfs/parameters/zfs_arc_max > /dev/null
fi

echo "==> Installing MongoDB 8.0"
sudo apt-get update
sudo apt-get install -y gnupg curl
if [ ! -f /usr/share/keyrings/mongodb-server-8.0.gpg ]; then
  curl -fsSL https://www.mongodb.org/static/pgp/server-8.0.asc | \
    sudo gpg -o /usr/share/keyrings/mongodb-server-8.0.gpg --dearmor
fi
echo "deb [ arch=amd64,arm64 signed-by=/usr/share/keyrings/mongodb-server-8.0.gpg ] https://repo.mongodb.org/apt/ubuntu jammy/mongodb-org/8.0 multiverse" | \
  sudo tee /etc/apt/sources.list.d/mongodb-org-8.0.list > /dev/null
sudo apt-get update
sudo apt-get install -y mongodb-org

echo "==> Configuring MongoDB (0.5 GB cache, replica set rs0)"
[ -f /etc/mongod.conf.orig ] || sudo cp /etc/mongod.conf /etc/mongod.conf.orig
sudo tee /etc/mongod.conf > /dev/null <<'EOF'
storage:
  dbPath: /var/lib/mongodb
  wiredTiger:
    engineConfig:
      cacheSizeGB: 0.5

systemLog:
  destination: file
  logAppend: true
  path: /var/log/mongodb/mongod.log

net:
  port: 27017
  bindIp: 127.0.0.1

processManagement:
  timeZoneInfo: /usr/share/zoneinfo

replication:
  replSetName: rs0
EOF
sudo systemctl enable mongod
sudo systemctl restart mongod

echo "==> Waiting for MongoDB"
for _ in $(seq 1 30); do
  mongosh --quiet --eval 'db.runCommand({ ping: 1 }).ok' > /dev/null 2>&1 && break
  sleep 1
done
mongosh --quiet --eval '
  try { rs.status(); print("Replica set already initiated"); }
  catch (e) { rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "localhost:27017" }] }); print("Replica set initiated"); }'

echo "==> Installing Redis"
sudo apt-get install -y redis-server
sudo sed -i 's/^appendonly no/appendonly yes/' /etc/redis/redis.conf
grep -q '^maxmemory ' /etc/redis/redis.conf || echo 'maxmemory 256mb' | sudo tee -a /etc/redis/redis.conf > /dev/null
grep -q '^maxmemory-policy ' /etc/redis/redis.conf || echo 'maxmemory-policy volatile-ttl' | sudo tee -a /etc/redis/redis.conf > /dev/null
sudo systemctl enable redis-server
sudo systemctl restart redis-server

echo
echo "==> Checks"
echo -n "MongoDB replica set ok: "; mongosh --quiet --eval 'rs.status().ok'
echo -n "Redis: "; redis-cli ping
echo "Done."
