// Collects every Cloudinary photo address the PUBLIC shop API shows (GET only, same endpoints the website's own build uses).
// usage: node crawl-photo-urls.mjs <api base, e.g. https://.../api/v1> <out folder>
// writes: <out>/urls.txt (distinct addresses, sorted), <out>/urls-by-source.json, <out>/crawl-report.txt
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const API = (process.argv[2] ?? "").replace(/\/$/, "");
const OUT = process.argv[3] ?? ".";
if (!API) {
  console.error("usage: node crawl-photo-urls.mjs <api base> <out folder>");
  process.exit(1);
}
mkdirSync(OUT, { recursive: true });

const CLOUD_RE = /https?:\/\/[a-z0-9.-]*cloudinary[a-z0-9.-]*\/[^\s"'<>)\\]+/gi;
const found = new Map(); // url -> Set(sources)
const problems = [];
let calls = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function get(path) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    calls++;
    try {
      const res = await fetch(API + path, { headers: { accept: "application/json" }, signal: AbortSignal.timeout(30000) });
      if (res.status === 204) return null;
      if (res.ok) return await res.json();
      if (res.status === 404) {
        problems.push(`404 ${path}`);
        return null;
      }
      if (res.status < 500 && res.status !== 429) {
        problems.push(`${res.status} ${path}`);
        return null;
      }
    } catch (e) {
      if (attempt === 4) problems.push(`error ${path}: ${e.message}`);
    }
    await sleep(500 * attempt * attempt);
  }
  problems.push(`gave up ${path}`);
  return null;
}

function collect(json, source) {
  if (json == null) return;
  const text = JSON.stringify(json);
  for (const m of text.matchAll(CLOUD_RE)) {
    const url = m[0].replace(/\\u0026/g, "&");
    if (!found.has(url)) found.set(url, new Set());
    found.get(url).add(source);
  }
}

// 1. every variant (paged), with the photos the list shows
const variantIds = [];
for (let page = 0; ; page++) {
  const p = await get(`/catalog/variants?size=100&page=${page}`);
  if (!p) break;
  collect(p.content, "variants");
  for (const v of p.content ?? []) variantIds.push(v.variantId);
  if (p.last || !(p.content ?? []).length) break;
}
console.log(`variants listed: ${variantIds.length}`);

// 2. every variant's own page (its gallery)
let next = 0;
async function worker() {
  while (next < variantIds.length) {
    const id = variantIds[next++];
    collect(await get(`/catalog/variants/${encodeURIComponent(id)}`), "variant-detail");
  }
}
await Promise.all([worker(), worker(), worker(), worker()]);
console.log(`variant pages read: ${variantIds.length}`);

// 3. categories, packages (list + each package page), the campaign
collect(await get("/catalog/categories"), "categories");
collect(await get("/catalog/promo-campaign"), "promo-campaign");
// the live backend may still be the version where packages were called kits
let pkgPath = "/catalog/packages";
let packages = await get(pkgPath);
if (packages == null) {
  if (problems.at(-1) === "404 /catalog/packages") problems.pop(); // expected on the old version
  pkgPath = "/catalog/kits";
  packages = await get(pkgPath);
}
packages = packages ?? [];
collect(packages, "packages");
for (const pk of packages) {
  if (pk.slug) collect(await get(`${pkgPath}/${encodeURIComponent(pk.slug)}`), "package-page");
}
console.log(`packages: ${packages.length}`);

const urls = [...found.keys()].sort();
const std = /^https:\/\/res\.cloudinary\.com\/[^/]+\/image\/upload\//;
const odd = urls.filter((u) => !std.test(u));
const bySource = {};
for (const u of urls) for (const s of found.get(u)) bySource[s] = (bySource[s] ?? 0) + 1;

writeFileSync(join(OUT, "urls.txt"), urls.join("\n") + "\n");
writeFileSync(join(OUT, "urls-by-source.json"), JSON.stringify(Object.fromEntries(urls.map((u) => [u, [...found.get(u)]])), null, 1));
const report = [
  `api: ${API}`,
  `when: ${new Date().toISOString()}`,
  `requests: ${calls}`,
  `distinct cloudinary addresses: ${urls.length}`,
  `by where they were seen: ${JSON.stringify(bySource)}`,
  `not the usual res.cloudinary.com/.../image/upload/ form: ${odd.length}`,
  ...odd.slice(0, 20).map((u) => `   ${u}`),
  `problems: ${problems.length}`,
  ...problems.slice(0, 30).map((p) => `   ${p}`),
].join("\n");
writeFileSync(join(OUT, "crawl-report.txt"), report + "\n");
console.log(report);
