# Nairobi Beauty Hub on the Contabo server

What runs on `158.220.99.163` for this shop, how to look after it, and how to take it away again. The history of every change made on the
server is `ops-log.md`. The plan and the reasons are in the frontend repo, `docs/vps-migration-plan-2026-10-26.md` and
`docs/handoff-2026-10-26.md` (sections 6, 10 and 11).

**The server is shared with the Moments Packaging projects (Moments Core is live production there).** Everything here is additive: it
lives in `/opt/nbh/`, on its own Docker network `nbh_media`, every container carries the label `com.nbh.project=nairobi-beauty-hub` and a
memory and CPU limit, and nothing of Moments' is touched. Never restart `coolify-proxy` or touch another project's container, volume or
database without the owner's word.

## What is there (photo stack, `media/`)

| Container | What it is | Limits |
|---|---|---|
| `nbh-garage` | Garage 2.5.0, an S3-compatible store; one node, SQLite metadata, fsync on; bucket `nbh-media` | 1 GB, 1 CPU |
| `nbh-imgproxy` | imgproxy 4.0.19: resized WebP copies on request, only from the presets (`o` = original, `w64`...`w1600`) | 2 GB, 3 CPU |
| `nbh-edge` | nginx: pretty addresses, a disk cache (10 GB), CORS, `s3.` host for the backend. The only NBH container on the shared `coolify` network | 512 MB, 1 CPU |

Addresses it serves: `https://img.trendyshaccosmetic.com/o/<key>` (the stored original) and `/w<width>/<key>` (a WebP copy); the S3 API for
the backend at `https://s3.trendyshaccosmetic.com`. A key is the Cloudinary path without its `v123` segment. **Not public until** the DNS
records `img` and `s3` (A records to 158.220.99.163, at GoDaddy) exist and `EDGE_PUBLIC=true` is set in `/opt/nbh/media/.env`
(`cd /opt/nbh/media && docker compose --env-file .env up -d edge`); the existing proxy then gets the two certificates by itself.

Secrets (the store's read/write key, the read-only key imgproxy uses, Garage's RPC and admin tokens) are generated on the server by
`install.sh` into root-only files (`.env`, `garage.toml`, `imgproxy.env`, mode 600) and are never printed or committed. The backend's key is
the one in `.env`; hand it over through a file, never in chat.

## Everyday commands (on the server, as root)

```bash
cd /opt/nbh/media
docker compose --env-file .env ps                                  # what runs
docker exec nbh-garage /garage status                              # the store's health
docker exec nbh-garage /garage bucket info nbh-media               # objects and size
docker logs --tail 50 nbh-imgproxy                                 # image service
# after changing a file in /opt/nbh/media/nginx (the folder is mounted whole, so a replaced file is seen):
docker exec nbh-edge nginx -t -c /etc/nginx/nbh/nginx.conf && docker exec nbh-edge nginx -s reload
```

From the PC, with the owner's SSH key (key only, never a password): `ssh -i <key> root@158.220.99.163 'bash -s' < scripts/preflight.sh`
(read-only look at the server), `scripts/smoke.sh <cloudinary photo urls>` (end-to-end test of store, edge and image service; cleans up after
itself), `scripts/recreate-edge.sh` (after changing the compose file or the nginx folder). Files go over with
`tar --owner=0 --group=0 -cf - <files> | ssh ... 'tar --no-same-owner -C /opt/nbh/media -xf -'`. Keep `< /dev/null` on any ssh that starts a
background job, or the session never returns.

## Copying the photos from Cloudinary (`copy/`)

`crawl-photo-urls.mjs <api base> <out folder>` lists every Cloudinary address the public shop API shows (GET only). `copy_photos.py` (run on
the server through `run-copy.sh`, in a throwaway container with its own limits) downloads each original (GET only, Cloudinary is never
written to), checks its size against what Cloudinary announced and that the bytes are an image, stores it under the same path, compares the
store's checksum and a read-back sha256, and appends a line to `mapping.jsonl`. Running it again skips what is stored. `--verify-only --deep`
re-reads everything; `--decode-check` asks imgproxy for a small copy of every photo. 26 Oct 2026: 2,311 photos, 2.19 GB, 0 problems.
Anything the public API does not show (drafts, old orders, hidden products, logos) has to be found in the database by the backend job.

## Before Cloudinary is ever emptied

One server disk is one copy, and Garage runs one node without redundancy. Make a second copy of the bucket somewhere else first (Contabo
Object Storage or Backblaze B2; `rclone copy`, never `sync`) and test a restore.

## Taking it away

`cd /opt/nbh/media && docker compose down` (the photos stay in `data/` until deleted by hand), then `docker network rm nbh_media` if it is
left, then `rm -rf /opt/nbh`.
