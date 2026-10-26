#!/bin/bash
# Recreates only the nbh-edge container after docker-compose.yml or the nginx/ folder changed, and empties its photo cache.
# (Nothing is public yet, so the cache only holds smoke-test entries.) Does not touch garage, imgproxy or any other project.
set -euo pipefail
cd /opt/nbh/media
rm -f nginx.conf photo-cache.conf          # the old single-file layout
docker compose --env-file .env stop edge
rm -rf data/cache/* 2>/dev/null || true
chown 101:101 data/cache
docker compose --env-file .env up -d edge
sleep 4
docker ps --filter label=com.nbh.project=nairobi-beauty-hub --format 'table {{.Names}}\t{{.Status}}'
docker exec nbh-edge nginx -t -c /etc/nginx/nbh/nginx.conf 2>&1
echo "mounted config files:"; docker exec nbh-edge ls -la /etc/nginx/nbh
