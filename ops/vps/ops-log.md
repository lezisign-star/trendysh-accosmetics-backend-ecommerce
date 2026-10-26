# Ops log: NBH on the Contabo server 158.220.99.163

Every change made on the server for Nairobi Beauty Hub, newest last. No secrets are written here (they live in root-only files on the server).
The server is shared with the Moments Packaging projects: only additions, nothing of theirs is touched, the proxy is never restarted.

## 26 Oct 2026

**08:39 (EAT) read-only pre-flight.** 24 GB RAM (2.4 GB used), 276 GB disk free, Docker 29.9 / Compose 5.6, `coolify-proxy` healthy with 0 restarts,
containers: the Coolify set, `moments-core-backend`, `moments-core-db`, `moments-store-db`. No `/opt/nbh`, no `nbh_*` network. DNS for `img.` and
`s3.trendyshaccosmetic.com` did not exist yet.

**08:40 photo stack installed** in `/opt/nbh/media` (`docker-compose.yml`, `install.sh`, `nginx/`), Compose project `nbh-media`:
- network `nbh_media` (private); only `nbh-edge` also joins Coolify's `coolify` network
- `nbh-garage` (dxflrs/garage:v2.5.0; S3 store, SQLite metadata, fsync on, a metadata snapshot every 6 h; 1 GB RAM / 1 CPU limit)
- `nbh-imgproxy` (darthsim/imgproxy:v4.0.19; presets only: `o` = original, `w64..w1600` = WebP at that width; read-only key; 2 GB / 3 CPU limit)
- `nbh-edge` (nginx:1.27-alpine; disk cache up to 10 GB in `/opt/nbh/media/data/cache`; 512 MB / 1 CPU limit)
- data in `/opt/nbh/media/data/{meta,blocks,cache}`; secrets in `/opt/nbh/media/.env`, `garage.toml`, `imgproxy.env` (all mode 600)
- Garage made the bucket `nbh-media` and a read/write key (in `.env`); `install.sh` made a read-only key `nbh-imgproxy-ro` for imgproxy
- the edge is NOT announced to the proxy (`EDGE_PUBLIC=false` in `.env`): no Traefik labels, no certificate requests, nothing reachable from outside

**08:42 to 08:47 smoke tests** (`scripts/smoke.sh`): one real PNG (2.2 MB) and one real JPG (44 KB) copied from Cloudinary into the bucket through the
`s3` host (nginx to Garage), read back through the `img` host: byte-identical (sha256 equal). WebP copies work (PNG 2.2 MB to 1.5 / 9.8 / 52 KB at 64 / 320 / 960 px),
MISS then HIT in the cache, a width not in the list, a missing photo and another host name all answer 404. Test objects deleted afterwards.
Memory use with traffic: garage 7 MB, imgproxy 30 MB, edge 8 MB.

Found and fixed while testing: (1) two `Cache-Control` headers (imgproxy's and ours) and ours on errors too: now one, on successful answers only;
(2) a single-file bind mount keeps pointing at the old file after the file is replaced on the host, so the nginx configuration folder is now mounted as a whole
(`/opt/nbh/media/nginx` at `/etc/nginx/nbh`, started with `-c /etc/nginx/nbh/nginx.conf`); edge recreated and its (test-only) cache emptied.
Images pulled on the server: dxflrs/garage:v2.5.0, darthsim/imgproxy:v4.0.19, nginx:1.27-alpine, rclone/rclone:1.69.3 (smoke test only).

**08:57 to 09:07 verified copy of the shop's photos from Cloudinary** (`copy/crawl-photo-urls.mjs` on this PC lists the Cloudinary addresses the PUBLIC shop API shows:
948 variants, their gallery pages, categories, the 3 packages = 2,311 distinct addresses, 730 JPG under `trendysh/products/`, 1,581 PNG under `products/`,
1 under `kits/`; then `copy/copy_photos.py`, run on the server by `copy/run-copy.sh` in a throwaway container `python:3.12-slim` with 512 MB / 2 CPU limits on the private network).
Cloudinary is only read (GET/HEAD); nothing there was written or deleted. Per photo: size equals what Cloudinary announced, the bytes are an image, stored under the same
path (`<folder>/<name>.<ext>`, the `v123` version segment dropped), the store's checksum equals ours, read back and sha256 equal; one line per photo in `copy/run1/mapping.jsonl`
(a copy is kept on this PC). Result: 2,311 photos, 2.19 GB, 0 failed, 232 s. Re-run skips everything already there. Then a deep check that re-reads all 2,311 objects and
compares sha256 (0 problems) and a decode check that asks imgproxy for a 64 px WebP of every photo (0 problems). The bucket holds 2,311 objects / 2.2 GB; disk 18 GB of 290 GB used.
Not covered by this list (the public API does not show them, the backend job must): image drafts, old orders' picture snapshots, hidden/inactive/deleted products, brand logos, profession pictures.

### To undo all of it
`cd /opt/nbh/media && docker compose down` (the photos stay in `data/` until deleted by hand), then `docker network rm nbh_media` if it is left, then `rm -rf /opt/nbh`.

### Still to do on the server
1. The owner adds DNS A records `img` and `s3` (trendyshaccosmetic.com) to 158.220.99.163 at GoDaddy.
2. Set `EDGE_PUBLIC=true` in `/opt/nbh/media/.env`, `docker compose --env-file .env up -d edge`: Traefik then gets the two certificates (HTTP challenge).
3. Check https from outside (certificate, CORS, headers).
4. Copy the photos from Cloudinary (verified copy, nothing on Cloudinary touched), then an offsite backup of the bucket before Cloudinary is ever emptied.
