#!/usr/bin/env python3
"""Verified copy of shop photos from Cloudinary into the NBH bucket (Garage, S3 API).

Cloudinary is only READ (plain GET/HEAD on the public delivery address); nothing is written to or deleted from it.
For every address: download -> check it is an image of exactly the size Cloudinary announced -> store it under the same
path -> read it back and compare checksums -> append a line to the mapping file. Running again skips what is stored and
verified, so an interrupted run just continues.

  python copy_photos.py --urls urls.txt --out ./run1 [--limit 20] [--workers 4]
  python copy_photos.py --verify-only --out ./run1          re-check every stored photo against the mapping
  python copy_photos.py --decode-check --out ./run1         ask the image service for a small copy of every photo (proves each one decodes)

Settings come from the environment: S3_ENDPOINT, S3_REGION, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY (never printed).
"""
import argparse
import concurrent.futures as cf
import datetime
import hashlib
import json
import os
import re
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

CLOUD_RE = re.compile(r"^https://res\.cloudinary\.com/(?P<cloud>[^/]+)/image/upload/(?P<rest>.+)$")
VERSION_RE = re.compile(r"^v\d+$")
TRANSFORM_RE = re.compile(r"^[a-z]{1,3}_[^/]*$")  # w_320,c_limit  /  f_auto  (a transformation segment)
CACHE_CONTROL = "public, max-age=31536000, immutable"
MAX_BYTES = 40 * 1024 * 1024
BLOCKED = {401, 403, 420, 429}

abort = threading.Event()
blocked_in_a_row = 0
lock = threading.Lock()
now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


class Failure(Exception):
    """This photo cannot be copied (permanent)."""


class Retry(Exception):
    """Try again."""


def s3():
    return boto3.client(
        "s3",
        endpoint_url=os.environ.get("S3_ENDPOINT", "http://nbh-garage:3900"),
        region_name=os.environ.get("S3_REGION", "garage"),
        aws_access_key_id=os.environ["S3_ACCESS_KEY"],
        aws_secret_access_key=os.environ["S3_SECRET_KEY"],
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}, retries={"max_attempts": 5, "mode": "standard"}),
    )


BUCKET = os.environ.get("S3_BUCKET", "nbh-media")


def parse(url):
    """-> (canonical original address, key). Transformations in the address are dropped: we want the original."""
    m = CLOUD_RE.match(url.strip())
    if not m:
        raise Failure("not a res.cloudinary.com /image/upload/ address")
    parts = m.group("rest").split("/")
    vi = next((i for i, p in enumerate(parts) if VERSION_RE.match(p)), -1)
    if vi >= 0:
        version, path_parts = parts[vi], parts[vi + 1 :]
    else:
        version = None
        path_parts = parts[1:] if TRANSFORM_RE.match(parts[0]) else parts
    path = "/".join(path_parts)
    key = urllib.parse.unquote(path)
    if not key or key.startswith("/") or ".." in key.split("/") or "" in key.split("/") or "\\" in key:
        raise Failure("unusable path")
    canonical = "https://res.cloudinary.com/%s/image/upload/%s%s" % (m.group("cloud"), (version + "/") if version else "", path)
    return canonical, key


def sniff(path):
    with open(path, "rb") as f:
        head = f.read(64)
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[4:8] == b"ftyp" and head[8:12] in (b"avif", b"avis"):
        return "image/avif"
    if head.lstrip()[:5] == b"<?xml" or head.lstrip()[:4] == b"<svg":
        return "image/svg+xml"
    return None


def download(url, dest):
    """GET the original. Returns dict(bytes, sha256, md5, content_type). Raises Failure / Retry."""
    global blocked_in_a_row
    req = urllib.request.Request(url, headers={"User-Agent": "nbh-photo-copy/1.0 (+one-off migration)"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status = r.status
            ctype = (r.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            clen = r.headers.get("Content-Length")
            sha, md5, n = hashlib.sha256(), hashlib.md5(), 0
            with open(dest, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    n += len(chunk)
                    if n > MAX_BYTES:
                        raise Failure("larger than %d MB" % (MAX_BYTES >> 20))
                    f.write(chunk)
                    sha.update(chunk)
                    md5.update(chunk)
    except urllib.error.HTTPError as e:
        why = e.headers.get("x-cld-error") or e.reason
        if e.code in BLOCKED:
            with lock:
                blocked_in_a_row += 1
                if blocked_in_a_row >= 5:
                    abort.set()
            raise Retry("http %s (%s)" % (e.code, why))
        if e.code in (404, 410):
            raise Failure("http %s (%s)" % (e.code, why))
        if e.code >= 500:
            raise Retry("http %s" % e.code)
        raise Failure("http %s (%s)" % (e.code, why))
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
        raise Retry("network: %s" % e)
    with lock:
        blocked_in_a_row = 0
    if status != 200:
        raise Failure("http %s" % status)
    if clen is not None and int(clen) != n:
        raise Retry("short read: got %d of %s bytes" % (n, clen))
    if n == 0:
        raise Failure("empty file")
    if not ctype.startswith("image/"):
        raise Failure("content type is %r, not an image" % ctype)
    kind = sniff(dest)
    if kind is None:
        raise Failure("the bytes do not look like an image")
    return {"bytes": n, "sha256": sha.hexdigest(), "md5": md5.hexdigest(), "content_type": kind if kind != ctype else ctype}


def remote_size(url):
    """Content-Length by HEAD (no body is transferred), or None."""
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "nbh-photo-copy/1.0"})
        with urllib.request.urlopen(req, timeout=30) as r:
            v = r.headers.get("Content-Length")
            return int(v) if v is not None else None
    except Exception:
        return None


def head(client, key):
    try:
        return client.head_object(Bucket=BUCKET, Key=key)
    except ClientError as e:
        if e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") == 404 or e.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
            return None
        raise


def read_back(client, key):
    r = client.get_object(Bucket=BUCKET, Key=key)
    sha, n = hashlib.sha256(), 0
    for chunk in r["Body"].iter_chunks(1 << 20):
        sha.update(chunk)
        n += len(chunk)
    return n, sha.hexdigest()


def copy_one(client, url):
    canonical, key = parse(url)
    rec = {"url": url, "canonical_url": canonical, "key": key}
    existing = head(client, key)
    if existing is not None:
        meta = existing.get("Metadata", {})
        size = existing["ContentLength"]
        announced = remote_size(canonical)
        if meta.get("sha256") and meta.get("source-url") == canonical and (announced is None or announced == size):
            rec.update(status="already_present", bytes=size, sha256=meta["sha256"], content_type=existing.get("ContentType"))
            return rec
    last = None
    for attempt in range(1, 5):
        if abort.is_set():
            raise Failure("stopped: Cloudinary keeps refusing (blocked or rate limited)")
        with tempfile.NamedTemporaryFile(prefix="photo-", delete=True) as tmp:
            try:
                got = download(canonical, tmp.name)
                store_key = key
                if existing is not None and existing.get("Metadata", {}).get("sha256") not in (None, got["sha256"]):
                    stem, dot, ext = key.rpartition(".")
                    store_key = "%s--%s%s%s" % (stem or key, got["sha256"][:8], dot, ext) if dot else "%s--%s" % (key, got["sha256"][:8])
                    rec["note"] = "the same path already held a different picture; stored beside it"
                with open(tmp.name, "rb") as body:
                    put = client.put_object(
                        Bucket=BUCKET, Key=store_key, Body=body, ContentType=got["content_type"], CacheControl=CACHE_CONTROL,
                        Metadata={"sha256": got["sha256"], "source-url": canonical, "copied-at": now()},
                    )
                etag = (put.get("ETag") or "").strip('"')
                if etag and "-" not in etag and etag != got["md5"]:
                    raise Retry("the store reports checksum %s, expected %s" % (etag, got["md5"]))
                n, sha = read_back(client, store_key)
                if n != got["bytes"] or sha != got["sha256"]:
                    raise Retry("read-back differs (%d bytes, sha256 %s...)" % (n, sha[:10]))
                rec.update(key=store_key, status="copied", bytes=got["bytes"], sha256=got["sha256"], content_type=got["content_type"])
                return rec
            except Retry as e:
                last = str(e)
        time.sleep(min(30, 2 ** attempt))
    raise Failure("gave up after 4 tries: %s" % last)


def run_copy(args):
    urls = [u.strip() for u in open(args.urls, encoding="utf-8") if u.strip()]
    urls = sorted(set(urls))
    if args.limit:
        urls = urls[: args.limit]
    os.makedirs(args.out, exist_ok=True)
    mapping_path = os.path.join(args.out, "mapping.jsonl")
    out = open(mapping_path, "a", encoding="utf-8", buffering=1)
    client = s3()
    counts = {"copied": 0, "already_present": 0, "failed": 0}
    total_bytes = 0
    started = time.time()

    def work(url):
        if abort.is_set():
            return {"url": url, "status": "failed", "error": "stopped before trying (Cloudinary keeps refusing)"}
        try:
            return copy_one(client, url)
        except Failure as e:
            return {"url": url, "status": "failed", "error": str(e)}
        except Exception as e:  # never let one photo stop the run
            return {"url": url, "status": "failed", "error": "unexpected: %r" % e}

    print("photos to do: %d (workers %d)" % (len(urls), args.workers), flush=True)
    with cf.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i, rec in enumerate(pool.map(work, urls), 1):
            rec["at"] = now()
            out.write(json.dumps(rec, ensure_ascii=True) + "\n")
            counts[rec["status"]] += 1
            total_bytes += rec.get("bytes", 0) if rec["status"] != "failed" else 0
            if rec["status"] == "failed":
                print("FAILED %s: %s" % (rec["url"], rec.get("error")), flush=True)
            if i % 100 == 0 or i == len(urls):
                print("%d/%d  copied %d, already there %d, failed %d  (%.0f MB, %.0fs)" % (
                    i, len(urls), counts["copied"], counts["already_present"], counts["failed"], total_bytes / 1e6, time.time() - started), flush=True)
    summary = {"finished": now(), "photos": len(urls), **counts, "megabytes": round(total_bytes / 1e6, 1), "seconds": round(time.time() - started),
               "stopped_early": abort.is_set()}
    json.dump(summary, open(os.path.join(args.out, "summary.json"), "w"), indent=1)
    print(json.dumps(summary), flush=True)
    return 0 if counts["failed"] == 0 else 2


def mapped(out):
    latest = {}
    for line in open(os.path.join(out, "mapping.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        if r.get("status") in ("copied", "already_present"):
            latest[r["key"]] = r
    return latest


def run_verify(args):
    client = s3()
    recs = mapped(args.out)
    bad = []
    for i, (key, r) in enumerate(sorted(recs.items()), 1):
        h = head(client, key)
        if h is None:
            bad.append((key, "missing from the bucket"))
        elif h["ContentLength"] != r["bytes"]:
            bad.append((key, "size %d, expected %d" % (h["ContentLength"], r["bytes"])))
        elif args.deep:
            n, sha = read_back(client, key)
            if sha != r["sha256"]:
                bad.append((key, "checksum differs"))
        if i % 500 == 0:
            print("checked %d/%d" % (i, len(recs)), flush=True)
    print("verified %d photos; problems: %d" % (len(recs), len(bad)))
    for k, why in bad[:50]:
        print("  PROBLEM %s: %s" % (k, why))
    return 0 if not bad else 2


def run_decode_check(args):
    """One small WebP copy of every photo through the image service: proves each file decodes."""
    recs = mapped(args.out)
    base = os.environ.get("EDGE_URL", "http://nbh-edge")
    host = os.environ.get("EDGE_HOST", "img.trendyshaccosmetic.com")
    bad = []

    def one(key):
        req = urllib.request.Request("%s/w64/%s" % (base, urllib.parse.quote(key)), headers={"Host": host})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                body = r.read()
                if r.status != 200 or not body.startswith(b"RIFF") or body[8:12] != b"WEBP":
                    return key, "answered %s but not a WebP" % r.status
        except Exception as e:
            return key, repr(e)
        return key, None

    with cf.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i, (key, why) in enumerate(pool.map(one, sorted(recs)), 1):
            if why:
                bad.append((key, why))
            if i % 500 == 0:
                print("decoded %d/%d" % (i, len(recs)), flush=True)
    print("decode check of %d photos; problems: %d" % (len(recs), len(bad)))
    for k, why in bad[:50]:
        print("  PROBLEM %s: %s" % (k, why))
    return 0 if not bad else 2


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--urls")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--deep", action="store_true", help="with --verify-only: read every photo back and compare its checksum")
    ap.add_argument("--decode-check", action="store_true")
    a = ap.parse_args()
    if a.verify_only:
        sys.exit(run_verify(a))
    if a.decode_check:
        sys.exit(run_decode_check(a))
    if not a.urls:
        ap.error("--urls is required")
    sys.exit(run_copy(a))
