#!/bin/bash
# Smoke test of the NBH photo stack, run ON the server: copies real photos from Cloudinary into the bucket through the s3 host,
# reads them back through the img host, compares checksums, checks the resized copies and the cache. Leaves nothing in the bucket.
# Secrets are read from /opt/nbh/media/.env and never printed.
# usage: smoke.sh <cloudinary photo url> [<cloudinary photo url> ...]
set -uo pipefail
cd /opt/nbh/media
set -a; . ./.env; set +a
SMOKE="_smoke-$(date +%s)-$RANDOM"   # a fresh prefix per run, so a cached answer from an earlier run can never pass for this one
WORK=/tmp/nbh-smoke; rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"

RCLONE_IMAGE=rclone/rclone:1.69.3
docker pull -q "$RCLONE_IMAGE" >/dev/null 2>&1 || { RCLONE_IMAGE=rclone/rclone:latest; docker pull -q "$RCLONE_IMAGE" >/dev/null; }
echo "rclone image: $RCLONE_IMAGE"

EDGE_IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "nbh_media").IPAddress}}' nbh-edge)
echo "edge address on nbh_media: $EDGE_IP"
rc() {
  docker run --rm --network nbh_media --add-host "s3.trendyshaccosmetic.com:$EDGE_IP" -v "$WORK:/data" \
    -e RCLONE_CONFIG_NBH_TYPE=s3 -e RCLONE_CONFIG_NBH_PROVIDER=Other \
    -e RCLONE_CONFIG_NBH_ENDPOINT=http://s3.trendyshaccosmetic.com -e RCLONE_CONFIG_NBH_REGION=garage \
    -e RCLONE_CONFIG_NBH_ACCESS_KEY_ID="$GARAGE_DEFAULT_ACCESS_KEY" -e RCLONE_CONFIG_NBH_SECRET_ACCESS_KEY="$GARAGE_DEFAULT_SECRET_KEY" \
    "$RCLONE_IMAGE" "$@"
}
# one request to the edge as the img host, from inside the edge container; prints status line and the headers that matter
edge_get() { # <path> <out file in container> ; prints headers
  docker exec nbh-edge sh -c "wget -S -O '$2' --header 'Host: img.trendyshaccosmetic.com' 'http://127.0.0.1$1' 2>&1 | grep -i -E 'HTTP/|content-type|content-length|x-cache|cache-control|access-control|etag' | sed 's/^ *//'"
}

i=0
for URL in "$@"; do
  i=$((i+1))
  KEY=$(echo "$URL" | sed -E 's#^https://res\.cloudinary\.com/[^/]+/image/upload/(v[0-9]+/)?##')
  echo; echo "================ photo $i: $KEY"
  curl -sS -L -m 180 -D "h$i.txt" -o "src$i.bin" "$URL" -w 'cloudinary download: http %{http_code}, %{size_download} bytes, %{time_total}s\n'
  grep -i -E '^(content-type|content-length):' "h$i.txt" | tr -d '\r'
  SRC_SHA=$(sha256sum "src$i.bin" | cut -d' ' -f1); echo "source sha256: $SRC_SHA"

  rc copyto "/data/src$i.bin" "nbh:nbh-media/$SMOKE/$KEY" --s3-no-check-bucket -q && echo "stored through the s3 host: ok" || echo "STORE FAILED"
  rc lsl "nbh:nbh-media/$SMOKE/" -q 2>&1 | tail -3

  echo "--- original through the img host"
  edge_get "/o/$SMOKE/$KEY" "/tmp/o$i.bin"
  EDGE_SHA=$(docker exec nbh-edge sha256sum "/tmp/o$i.bin" | cut -d' ' -f1)
  [ "$EDGE_SHA" = "$SRC_SHA" ] && echo "ORIGINAL MATCHES the source (sha256 equal)" || echo "ORIGINAL DIFFERS: $EDGE_SHA"

  for WIDTH in 64 320 960; do
    echo "--- /w$WIDTH/ (first request, then again)"
    edge_get "/w$WIDTH/$SMOKE/$KEY" "/tmp/w$WIDTH-$i.bin"
    docker exec nbh-edge sh -c "echo \"bytes: \$(wc -c < /tmp/w$WIDTH-$i.bin)  first 12 bytes: \$(head -c 12 /tmp/w$WIDTH-$i.bin | od -An -c | tr -s ' ')\""
    edge_get "/w$WIDTH/$SMOKE/$KEY" "/tmp/w$WIDTH-$i-again.bin" | grep -i -E 'x-cache'
  done
done

echo; echo "================ checks that must be refused or empty"
echo "--- a width that is not in the list (/w333/)"
K1=$(echo "$1" | sed -E 's#^https://res\.cloudinary\.com/[^/]+/image/upload/(v[0-9]+/)?##')
edge_get "/w333/$SMOKE/$K1" "/tmp/bad.bin" | head -8
echo "--- a photo that does not exist"
edge_get "/o/$SMOKE/nothing-here.png" "/tmp/none.bin" | head -8
echo "--- another host name"
docker exec nbh-edge sh -c "wget -S -O /dev/null --header 'Host: example.org' http://127.0.0.1/o/$SMOKE/x 2>&1 | grep -i 'HTTP/' | sed 's/^ *//'"

echo; echo "================ cleaning up the test objects"
rc purge "nbh:nbh-media/$SMOKE" -q && echo "removed"
rc lsl "nbh:nbh-media/" -q 2>&1 | head -3
docker exec nbh-edge sh -c 'rm -f /tmp/*.bin'
rm -rf "$WORK"

echo; echo "================ resource use now"
docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}|{{.CPUPerc}}' nbh-garage nbh-imgproxy nbh-edge
echo; echo "imgproxy log (last lines)"; docker logs --tail 12 nbh-imgproxy 2>&1 | cut -c1-220
