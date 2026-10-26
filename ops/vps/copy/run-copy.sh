#!/bin/bash
# Runs copy_photos.py in a throwaway container on the NBH private network (limits: 512 MB, 2 CPUs). Run ON the server.
#   run-copy.sh <run name> <copy_photos.py arguments...>
#   e.g.  run-copy.sh run1 --urls /work/run1/urls.txt --out /work/run1 --limit 20
#         DETACH=1 run-copy.sh run1 --urls /work/run1/urls.txt --out /work/run1        (keeps running after you disconnect; follow with: tail -f /opt/nbh/copy/run1/run.log)
# The photo store's key is read from /opt/nbh/media/.env and handed over as environment variables; it is never printed.
set -euo pipefail
cd /opt/nbh/media
set -a; . ./.env; set +a
NAME="$1"; shift
mkdir -p "/opt/nbh/copy/$NAME"
MODE=(--rm)
[ "${DETACH:-0}" = "1" ] && MODE=(--rm -d)
docker run "${MODE[@]}" --name "nbh-copy-$NAME" --network nbh_media --memory 512m --cpus 2 \
  --label com.nbh.project=nairobi-beauty-hub \
  -v /opt/nbh/copy:/work \
  -e S3_ENDPOINT=http://nbh-garage:3900 -e S3_REGION=garage -e S3_BUCKET=nbh-media \
  -e S3_ACCESS_KEY="$GARAGE_DEFAULT_ACCESS_KEY" -e S3_SECRET_KEY="$GARAGE_DEFAULT_SECRET_KEY" \
  -e PYTHONUNBUFFERED=1 -e PIP_DISABLE_PIP_VERSION_CHECK=1 \
  python:3.12-slim sh -c "pip install -q --no-cache-dir 'boto3<1.36' >/dev/null 2>&1 && python /work/copy_photos.py $* 2>&1 | tee -a /work/$NAME/run.log"
