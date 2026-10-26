#!/bin/bash
# Installs (or updates) the NBH photo stack in /opt/nbh/media. Safe to run again: secrets that exist are kept.
# Run as root on the server, from /opt/nbh/media, where docker-compose.yml and the nginx/ folder have been copied.
# Secrets are generated here, kept in files only root can read, and never printed.
set -euo pipefail

BASE=/opt/nbh/media
[ "$(cd "$(dirname "$0")" && pwd)" = "$BASE" ] || { echo "run this script from $BASE"; exit 1; }
[ -f "$BASE/docker-compose.yml" ] && [ -f "$BASE/nginx/nginx.conf" ] && [ -f "$BASE/nginx/photo-cache.conf" ] || { echo "docker-compose.yml and nginx/ are missing"; exit 1; }
umask 077
mkdir -p "$BASE/data/meta" "$BASE/data/blocks" "$BASE/data/cache"
chmod 755 "$BASE" "$BASE/nginx"
cd "$BASE"
# nginx's workers (uid 101 in the alpine image) write the photo cache
chown 101:101 data/cache

# --- secrets and Garage's own configuration (created once) ---
if [ ! -f .env ]; then
  printf 'EDGE_PUBLIC=false\nGARAGE_DEFAULT_ACCESS_KEY=GK%s\nGARAGE_DEFAULT_SECRET_KEY=%s\n' "$(openssl rand -hex 12)" "$(openssl rand -hex 32)" > .env
  chmod 600 .env
  echo "created .env (the read/write key the backend will use)"
fi
if [ ! -f garage.toml ]; then
  cat > garage.toml <<EOF
metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
# SQLite rather than LMDB: LMDB can be corrupted by an unclean shutdown, and one small node does not need its speed.
db_engine = "sqlite"
metadata_fsync = true
data_fsync = true
# a copy of the metadata every 6 hours (the last two are kept in meta/snapshots)
metadata_auto_snapshot_interval = "6h"
# photos are already compressed
compression_level = "none"
replication_factor = 1

rpc_bind_addr = "0.0.0.0:3901"
rpc_public_addr = "127.0.0.1:3901"
rpc_secret = "$(openssl rand -hex 32)"

[s3_api]
s3_region = "garage"
api_bind_addr = "0.0.0.0:3900"

[admin]
api_bind_addr = "0.0.0.0:3903"
admin_token = "$(openssl rand -base64 32)"
metrics_token = "$(openssl rand -base64 32)"
EOF
  chmod 600 garage.toml
  echo "created garage.toml"
fi
# compose reads this file for imgproxy; it must exist (empty) before Garage is started on its own
[ -f imgproxy.env ] || { : > imgproxy.env; chmod 600 imgproxy.env; }

echo "pulling images"
docker pull -q dxflrs/garage:v2.5.0 >/dev/null
docker pull -q darthsim/imgproxy:v4.0.19 >/dev/null
docker pull -q nginx:1.27-alpine >/dev/null

# --- Garage first: its keys are needed by imgproxy ---
docker compose --env-file .env up -d garage
echo -n "waiting for garage"
for i in $(seq 1 45); do
  if docker exec nbh-garage /garage status >/dev/null 2>&1; then echo " ... up"; break; fi
  echo -n "."; sleep 2
  if [ "$i" = 45 ]; then echo " ... did not come up"; docker logs --tail 30 nbh-garage; exit 1; fi
done

# the read-only key imgproxy uses (it only ever reads photos)
if [ ! -s imgproxy.env ]; then
  if ! docker exec nbh-garage /garage key info nbh-imgproxy-ro >/dev/null 2>&1; then
    docker exec nbh-garage /garage key create nbh-imgproxy-ro > keycreate.out
  fi
  docker exec nbh-garage /garage bucket allow --read nbh-media --key nbh-imgproxy-ro >/dev/null
  RO_ID=$(grep -m1 -i 'key id' keycreate.out | awk '{print $NF}')
  RO_SECRET=$(grep -m1 -i 'secret key' keycreate.out | awk '{print $NF}')
  if [ -z "$RO_ID" ] || [ -z "$RO_SECRET" ]; then echo "could not read the key Garage made (see keycreate.out)"; exit 1; fi
  WIDTHS="64 72 96 128 160 200 240 320 360 400 480 640 720 960 1280 1600"
  PRESETS="o=raw:1"
  for w in $WIDTHS; do PRESETS="$PRESETS,w$w=rs:fit:$w:0"; done
  cat > imgproxy.env <<EOF
IMGPROXY_BIND=:8080
IMGPROXY_USE_S3=true
IMGPROXY_S3_ENDPOINT=http://nbh-garage:3900
IMGPROXY_S3_REGION=garage
AWS_ACCESS_KEY_ID=$RO_ID
AWS_SECRET_ACCESS_KEY=$RO_SECRET
IMGPROXY_BASE_URL=s3://nbh-media/
IMGPROXY_ALLOWED_SOURCES=s3://nbh-media/
IMGPROXY_ONLY_PRESETS=true
IMGPROXY_PRESETS=$PRESETS
IMGPROXY_QUALITY=82
IMGPROXY_FORMAT_QUALITY=webp=80
IMGPROXY_MAX_SRC_RESOLUTION=60
IMGPROXY_MAX_SRC_FILE_SIZE=30000000
IMGPROXY_WORKERS=4
IMGPROXY_TTL=31536000
IMGPROXY_USE_ETAG=true
IMGPROXY_LOG_LEVEL=info
EOF
  chmod 600 imgproxy.env
  rm -f keycreate.out
  echo "created imgproxy.env"
fi

docker compose --env-file .env up -d imgproxy edge
sleep 5
echo
echo "=== running"
docker ps --filter label=com.nbh.project=nairobi-beauty-hub --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
echo
echo "=== garage"
docker exec nbh-garage /garage status 2>&1 | head -8
docker exec nbh-garage /garage bucket list 2>&1 | head -5
echo
echo "public exposure: EDGE_PUBLIC=$(grep '^EDGE_PUBLIC=' .env | cut -d= -f2)  (set it to true and run 'docker compose up -d edge' once the DNS records exist)"
