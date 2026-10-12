# Variants v2: build plan

Written 2026-10-12 for a separate Claude session to carry out. Covers both repos:

- Backend: `J:\backend\nairobi-beauty-hub-backend` (Spring Boot, Flyway, branch `main`)
- Frontend: `J:\frontend\nairobi-beauty-hub-main` (storefront + admin, branch `staging`)
- `J:\frontend\_archived-nairobi-beauty-hub-duplicates\nairobi-market-mockup` is an old prototype. **Do not touch it.**

Approved mockup of the customer side and the admin side after "Generate":
https://claude.ai/artifact/X1W22T3ncj1qkAg2sgxYv5 (four tabs: Shop, Product page, Cart & order, Admin after Generate). Match it.

Read both `HANDOFF.md` files first, especially the backend's "2026-10-10 (generate variants)" entry (V61). This plan builds on that work; it does not start over.

---

## 1. What the owner asked for (the requirements, in their words turned into rules)

1. **Staff add 100+ variants quickly and without mistakes.** Many products come in every colour in every size; some sizes miss some colours.
2. **Adding variants is a wizard:**
   - A checkbox "has colours". If ticked, staff list the colours, and each colour gets a swatch, which the AI suggests and staff confirm.
   - A checkbox "has sizes". If ticked, staff list the sizes in order.
   - If both are ticked, a checkbox "every size comes in every colour" (on by default). Unticked, a size × colour grid of checkboxes appears, so staff untick the combinations that don't exist.
   - **Prices**, in one of four modes:
     - same for all
     - step by size, ascending or descending: a start price plus a step, auto-filled
     - one price per size
     - every cell its own price
   - Whichever mode is used, staff then see the full price grid and can edit any single cell.
   - An optional **opening stock** per cell, in the same grid.
   - **Exceptions must be easy** (owner, 2026-10-12): often just one or a few variants break the pattern.
     - **Price exceptions:** one colour costs more in every size ("Gold Shimmer +150"), or a single variant has its own price.
     - **Size exceptions:** one size comes in only a few colours ("Travel 1g only in Ruby Red and Nude Beige"), or one colour has an extra size.
     - Staff set the rule once and add exceptions in a couple of clicks. They never have to switch to "every cell its own price" just because of one odd variant.
3. **Each variant has its own photo.**
   - The colour change comes from the AI.
   - For sizes, staff may give the **label text** that size's packaging shows ("100ml"). They may also upload a **photo of that size**, so the physical size difference shows. Both are optional.
4. **The name is the product name plus the option:** "Velvet Matte Lipstick – Ruby Red / 50ml", or "– Ruby Red" for colour only, or "– 50ml" for size only. This already exists (`VariantCombos.variantName`).
5. **Descriptions are written by the AI** from the product photo, name and details plus the variant's colour/size. It never invents ingredients or claims (the existing rules in `DescriptionCaps` and the prompt still apply).
6. **Variants go live by default.** "Let me approve first" is an option.
7. **Several generation jobs run at the same time** while staff keep working. Jobs survive a server restart and pause and resume by themselves when the Gemini quota runs out.
8. **One "Needs attention" place on the admin dashboard** lists everything a person must do, each with a button that does it. Items clear themselves when fixed, and a count shows in the admin header, updated live.
9. **The customer side:**
   - **Every variant is listed as its own product** in the shop (one card per variant, as today).
   - Under each card, dots for the same product's other colours.
   - On the product page, colour swatches first and then sizes. Sizes not made in the chosen colour are disabled, sold-out options are marked, and the photo, name, price, SKU and stock follow the choice.
10. **What the customer picked is exactly what staff see**: name, colour (with its dot), size, SKU and price on the order. It's saved at checkout, so later edits never change old orders.
11. **Shop filters cover everything:**
    - category
    - colour (by family, so "Red" finds Ruby Red and Cherry)
    - size (the sizes that exist in the chosen category)
    - price range
    - brand
    - in stock
    - on deal
    - wholesale price available
    - sort
    - active-filter chips with "Clear all"

House rules (both HANDOFFs show them):
- Plain wording in the UI.
- No decorative emoji or sparkle/star icons; functional icons only.
- Additive migrations.
- A new frontend must still work against the old backend (new fields optional).
- Write a HANDOFF entry per phase.
- Commit per phase.
- **Do not push unless the owner says so.**

---

## 2. What exists today (verified 2026-10-12)

**Backend**
- `catalog/entity/ProductVariant.java` is the sellable SKU.
  - Fields: `optionLabel`, `optionColor`, `optionSize`, `swatchHex`, `generatedFromVariantId`, `generationBatchId` (V61), `sellingPrice`, deal, wholesale, `isActive`, `sortOrder`.
  - `displayLabel()` and `shortOption()` build the name customers and staff see.
- `variantgen/` holds the current generator:
  - `VariantCombos` builds the full colour × size grid only, capped at 150.
  - `VariantGenerationRunner` is `@Async` and rides on an `image_cleanup_batch` row.
  - `VariantGenerationWriter` creates the variants with **one shared price**.
  - `VariantPlanner` uses Gemini text for descriptions and swatches.
  - `VariantPhotoPromptBuilder` builds the photo prompt.
- **Only one run at a time:** `VariantGenerationService.newBatch` throws 409 when any batch is `IN_PROGRESS`.
- **One Gemini photo per variant,** each recoloured from the base photo, so sizes of the same colour are drawn separately.
- **No preview or validation step.** Duplicate detection compares label text only.
- **`@Async` jobs are lost on restart.** A batch stays `IN_PROGRESS` and then blocks every later run.
- `cart/entity/CartItem.java` points at the variant and snapshots the price and names.
- `order/entity/CustomerOrderItem.java` points at the variant and snapshots the price, but **not the option, SKU or name**.
  - `OrderItemDto` and `AdminOrderDetailDto` read `variant.shortOption()` live.
  - Line items are created in `order/service/CheckoutService.java` (around line 269).
- Catalog API:
  - `GET /api/v1/catalog/variants` returns `CatalogVariantCardDto`, one card per sellable variant.
  - `GET /catalog/variants/{id}` returns `CatalogVariantDetailDto`, including `siblings`.
  - `optionColor`, `optionSize` and brand are **not** on the card.
- Stock:
  - `PUT /products/{id}/variants/{vid}/stock` with `receivedQty` goes through `VariantStockService.addOnHand`.
  - `POST /api/v1/stock/receive` takes a list of lines (`ReceiveStockRequest`).
  - A gain whose cost is unknown needs `allowUnknownCost`.
- Live updates:
  - `common/event/DataChangedEvent.Topic` drives the admin SSE (`live/AdminEventStreamController`).
  - `isPublic()` decides whether the storefront hears a topic.
- Gemini: `imagecleanup/GeminiImageClient` and `GeminiTextClient`, which flag quota errors with `isQuotaExceeded()`. Cloudinary is used through `CloudinaryUploader`.
- Latest migration: `V61__generated_variants.sql`. **The next one is V62.**

**Frontend**
- Storefront:
  - `src/routes/shop.tsx` filters the whole catalogue in memory: category, in stock, on sale, max price, sort.
  - `src/routes/product/$id.tsx` is the variant page. Its picker is one flat list of siblings (lines ~330–420).
  - `src/lib/catalogApi.ts` (`toProduct`) and `src/data/types.ts` (`Product`) map and type the cards.
- Admin:
  - `src/components/admin/GenerateVariantsDialog.tsx` is the current dialog.
  - `src/lib/variantGenApi.ts` calls the generator.
  - `src/routes/admin.products_.$productId.tsx` is the product editor.
  - `src/routes/admin.index.tsx` is the dashboard.
- Public pages are prerendered at build: `scripts/prerender.mjs`, run by `npm run build`, rebuilt nightly by the backend.

---

## 3. Design decisions (made; do not re-open without the owner)

### 3.1 Data model (V62, additive)

**`product_option_value`**: the product's own list of colours and sizes, which drives the picker's order and the wizard.

```
id uuid pk, product_id uuid fk, kind varchar(10) check in ('COLOR','SIZE'),
value varchar(80) not null, position int not null,
swatch_hex varchar(9) null, color_family varchar(20) null,      -- COLOR only
size_label_text varchar(60) null, size_photo_image_id uuid null,  -- SIZE only (photo = product_image id)
unique (product_id, kind, lower(value))
```

**`product_variant`**: add `color_family varchar(20)` and `size_position int`. Keep `option_color` / `option_size` / `swatch_hex`; they're the variant's own copy, which is cheap to read on cards.

**Colour families** (fixed list, a Java enum plus a TS const): Red, Pink, Coral, Orange, Nude, Brown, Purple, Blue, Green, Yellow, Gold, Silver, White, Black, Grey, Clear, Multi.
- Derived from the hex (hue/lightness buckets) when staff don't pick one.
- Staff can always override.

**`customer_order_item`** snapshots, all nullable:
- `sku_snapshot`
- `variant_name_snapshot`
- `option_color_snapshot`
- `option_size_snapshot`
- `swatch_hex_snapshot`
- `image_url_snapshot`

**`variant_job`**:

```
id uuid pk, product_id uuid fk, status varchar(24)   -- QUEUED, RUNNING, PAUSED_QUOTA, AWAITING_APPROVAL, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED
approve_first bool, request jsonb (the validated wizard body), created_by uuid, created_at, updated_at,
started_at, finished_at, next_attempt_at, locked_by varchar(80), heartbeat_at, last_error text,
variants_total int, descriptions_done int, photos_done int, photos_failed int
```

**`variant_job_item`**: one per variant to make.

```
id uuid pk, job_id fk, variant_id uuid null (set after create), color varchar(80), size varchar(80),
price numeric(14,2), opening_stock numeric(14,3) null, unit_cost numeric(14,4) null,
description_status varchar(12), photo_status varchar(12)  -- PENDING, DONE, FAILED, SKIPPED
photo_attempts int, last_error text
```

**There is no stored "attention" table.** Needs-attention items are **computed from state** (see 3.5), so they can't go stale.

### 3.2 Photos

- **Per colour,** make one *colour master*: the main product photo (or the size's own photo, below) recoloured. That's one Gemini call per colour per base photo.
- **Per variant:**
  - The size has a **size photo** → recolour that photo instead of the main one.
  - The size has **label text** → one more Gemini edit of the colour master, changing only the label text to it.
  - Neither → the variant gets **its own copy** of the colour master. Upload it again under the variant's own public id; no Gemini call.
- **Every variant ends up with its own `product_image` row,** so the "a photo belongs to exactly one option" rule (V56/V57) still holds.
- Reuse `VariantPhotoPromptBuilder`. Add a "label text only" prompt that changes the printed size and nothing else.
- Until a variant's photo exists, the card and page use the product's main photo. Check `CatalogService.toCard`'s image fallback and keep it.
- `approveFirst` → photos go in as drafts (existing photo studio drafts), and variants stay hidden until `/publish`.

### 3.3 Job engine (replaces `@Async` and the single-run lock for variant jobs)

- **The database queue is the source of truth.**
  - A `@Scheduled(fixedDelay = 3s)` poller claims jobs with `SELECT … FOR UPDATE SKIP LOCKED`.
  - Claims only `QUEUED`, `PAUSED_QUOTA` whose `next_attempt_at <= now()`, or `RUNNING` with `heartbeat_at` older than 5 min (a crashed worker).
  - It runs them on a dedicated `ThreadPoolTaskExecutor`. Copy `common/config/ImageAssetExecutorConfig`.
  - **Concurrency:** `app.variant-jobs.workers`, default 3.
- **Steps are idempotent and resumable,** driven by `variant_job_item` statuses:
  1. **CREATE**, in one transaction:
     - Upsert `product_option_value` rows.
     - Create the variants: SKU via `productService.nextSku`, name via `VariantCombos.variantName`, colour/size/family/swatch/position, price from the item, the base's unit/VAT/weight, `isActive = !approveFirst`.
     - Set `variant_job_item.variant_id`.
     - Post opening stock through `POST /stock/receive` logic (`ReceiveStockRequest` lines). Pass `allowUnknownCost` when no cost was given, and note on the item that the cost is unknown.
     - Skip combinations that already exist on the product, matched on (lower(color), lower(size)) and **not** on label text.
  2. **DESCRIPTIONS**: Gemini text in chunks of 40, as today. Pass the product's main photo now (`GeminiTextClient.describe` accepts a photo). Mark items `DONE` or `FAILED`; a failure here never stops photos.
  3. **PHOTOS**: as in 3.2. Items are processed colour by colour, so a colour's master is made once and cached for the job.
- **A shared Gemini limiter** (one bean) is used by both the variant jobs and the existing photo studio runner.
  - It's a semaphore plus requests-per-minute from config, so many jobs share fairly instead of tripping the quota.
- **On a quota error:** the job becomes `PAUSED_QUOTA` with `next_attempt_at` set to now plus `Retry-After` (or 15 min). It resumes on its own.
- **Heartbeat** every item. Cancel sets `CANCELLED`; the worker checks between items.
- **Finished states:**
  - When every item is done → `COMPLETED`, or `COMPLETED_WITH_ERRORS` if any photo or description failed.
  - With `approveFirst` → `AWAITING_APPROVAL` until published.
- Publish `DataChangedEvent` with a new staff-only topic `VARIANT_JOBS` on every state change. Throttle progress events to one per job per 2 s.
- The old `variantgen` endpoints stay working, but the admin stops using them. Mark them deprecated in their javadoc. Leave the photo studio's own batch lock alone.

### 3.4 Wizard API (`PERM_PRODUCT_MANAGE`, base `/api/v1/admin/variant-jobs`)

- **`POST /suggest-swatches`** `{names: string[]}` → `[{name, hex, family}]`.
  - Gemini text first, then a built-in colour-name dictionary if Gemini isn't configured or fails.
  - Synchronous. Used while staff type colours.
- **`POST /preview`** with the wizard body (below) → the full plan. It makes no writes. Returns:
  - every row: color, size, name, preview SKU pattern, price, stock, `exists` flag
  - `errors[]` (block Generate) and `warnings[]` (allowed)
  - an estimate: variants to make, Gemini calls, rough minutes
- **`POST /`**, same body → re-runs the validation on the server (never trust the client) and creates a `QUEUED` job → the job DTO.
- **`GET /?status=active|recent`**, `GET /{id}` (job + items + variant thumbnails), `POST /{id}/publish`, `POST /{id}/retry` (failed items only), `POST /{id}/cancel`.
- **`GET /api/v1/admin/attention`** → items (3.5). Permission: any staff with product, stock or order rights; filter items by permission.

**Wizard body:**

```jsonc
{
  "productId": "…", "baseVariantId": "…?",
  "colors": [{"name": "Ruby Red", "hex": "#9B111E", "family": "Red"}],      // empty = no colours
  "sizes":  [{"name": "50ml", "labelText": "50ml?", "photoImageId": "…?"}],  // in order; empty = no sizes
  "everyCombination": true,
  "cells": [{"color": "Ruby Red", "size": "50ml", "include": true, "price": 1100, "openingStock": 12, "unitCost": 600}],
  "pricing": {                                     // how the cells were filled; stored on the job so "Add colours or sizes" reopens it
    "mode": "SAME|STEP_UP|STEP_DOWN|PER_SIZE|PER_CELL",
    "colorAdjustments": [{"color": "Gold Shimmer", "amount": 150}],   // +/- for that colour in every size
    "cellOverrides": [{"color": "Ruby Red", "size": "Travel 1g", "price": 400}]
  },
  "sizeAvailability": [{"size": "Travel 1g", "onlyColors": ["Ruby Red", "Nude Beige"]}],  // shortcut; also reflected in cells[].include
  // cells[] always carries the FINAL price and include flag; the server validates cells and checks they agree with pricing + sizeAvailability (mismatch = 422)
  "baseOption": {"color": "Nude Beige", "size": "30ml"},  // what the existing base variant is, if it has no colour/size yet
  "approveFirst": false,
  "prompt": "optional extra notes for descriptions/labels"
}
```

**Validation errors (block Generate):**
- An included cell with no price, or a price ≤ 0.
- The same colour or size twice (case- and space-insensitive).
- An empty colour or size name. A name over 60 characters.
- Nothing to make (everything excluded or already existing).
- More than 150 new variants in one job.
- No base photo while there is no size photo either.
- An invalid hex.
- Opening stock that's negative or a fraction for a `PIECE` unit.

**Warnings (shown, don't block):**
- A bigger size (by position) is cheaper than a smaller one in the same colour.
- A cell more than 3× or less than ⅓ of the median price of its row/column (catches a typo like 12,000 vs 1,200).
- Combinations that already exist (listed, will be skipped).
- Opening stock entered with no cost.
- Two colours with nearly identical swatches (ΔE < 5).
- **Exceptions summary** (not a warning, always shown):
  - "Prices: step 400 → 650 → 1,100. Exceptions: Gold Shimmer +150 in every size; Ruby Red / Travel 1g = 400."
  - "Sizes: Travel 1g only in Ruby Red, Nude Beige."
  - Staff confirm exactly what's different before generating.

### 3.5 Needs attention (computed)

Each item: `{key, severity: CRITICAL|ACTION|INFO, title, detail, productId?, jobId?, variantIds?, actions: [{label, kind}]}`.

| Source | Item | Actions |
|---|---|---|
| job items with `photo_status = FAILED` | "N photos failed for {product}" (with option names) | Retry photos · Upload my own (opens the product's photo manager on that variant) |
| job `AWAITING_APPROVAL` | "{product}: N variants waiting for approval" | Review & publish |
| job `PAUSED_QUOTA` | "AI quota reached: {product} resumes around HH:MM" | none (INFO) |
| job `FAILED` | "{product}: generation stopped: {last_error}" | See details · Restart |
| variants made by a job, live, with on-hand 0 and never received | "{product}: N new variants have no stock" | Add stock (opens the stock grid for those variants) |
| job items with `description_status = FAILED` | "{product}: N descriptions not written" | Retry |

- Items vanish as soon as the underlying state changes.
- The header count is the number of CRITICAL + ACTION items.
- The frontend refetches on the `VARIANT_JOBS`, `STOCK` and `PRODUCTS` topics.

### 3.6 Catalogue API additions (all optional fields, so an old frontend is unaffected)

- **`CatalogVariantCardDto`** gains `optionColor`, `optionSize`, `colorFamily`, `sizePosition`, `brandName`.
  - Fill them in `CatalogService.toCard`.
  - Siblings in `CatalogVariantDetailDto` get the same.
- **`CatalogVariantDetailDto`** gains `colors: [{value, hex, family, position}]` and `sizes: [{value, position}]` from `product_option_value`, so the picker knows the order even when some combinations are sold out or missing.
  - If a product has no option rows (older products), build the lists from the siblings.
- **Order DTOs** (`OrderItemDto`, `AdminOrderDetailDto.Line`, order emails, delivery/hand-off messages) read the **snapshot first** and fall back to the live variant for old orders.
  - Grep every caller of `displayLabel()` / `shortOption()` that describes an *order line*.

---

## 3.7 Phase 3 decisions added 2026-10-12, after Phases 1–2 shipped (read before starting Phase 3)

- **Numbering:** Phase 1 took V62 and Phase 2 took V63 (`product_option_value`, `product_variant.color_family` / `size_position`, `ColorFamily`, `ProductOptionsService.sync`). The job tables are **V64**.
- **Approve-first without photo drafts.** `image_draft.batch_id` is tied to `image_cleanup_batch`, and creating such rows would show variant jobs as photo studio runs and could trip its one-run lock. Instead:
  - Approve-first creates the variants hidden (`is_active=false`) and attaches their photos directly. A hidden variant's photos never reach the shop.
  - `POST /{id}/publish` makes them visible.
  - Review = the job's variant list with thumbnails.
  - Note this change from 3.2 in the HANDOFF.
- **The pricing body needs the rule's numbers.** 3.4's `pricing` object lacks them. Use:
  ```jsonc
  "pricing": {"mode": "SAME|STEP_UP|STEP_DOWN|PER_SIZE|PER_CELL",
              "price": 1100,                       // SAME
              "start": 400, "step": 250,           // STEP_UP / STEP_DOWN, applied in size order
              "sizePrices": {"50ml": 900},         // PER_SIZE
              "colorAdjustments": [...], "cellOverrides": [...]}
  ```
  - **The server computes every cell's price:** cell override, else rule(size) + colour adjustment. For PER_CELL, `cells[].price` is required.
  - A `cells[].price` that disagrees with the computed price gives 422.
  - STEP modes need sizes.
- **Colour and size management** (owner asked "are those colours manageable by staff and admins?"). These are new endpoints under `PERM_PRODUCT_MANAGE`; the screen comes in Phase 4.
  - `GET /api/v1/products/{id}/options`: the product's list (rows from V63, with effective family).
  - `PATCH /api/v1/products/{id}/options/{optionId}` with `{value?, swatchHex?, colorFamily?, sizeLabelText?, sizePhotoImageId?}`.
    - **Renaming** updates every variant with that value: `optionColor`/`optionSize` and the label. A variant whose name is still the generated `VariantCombos.variantName(product, oldLabel)` gets the new name; a hand-written name is left alone.
    - **A dot or family change** applies to all of that colour's variants.
    - **Renaming to a value already on the list** gives 409, with no silent merge.
  - `PUT /api/v1/products/{id}/options/order` `{kind, ids[]}` reorders. It refreshes each variant's `sizePosition` and keeps the list complete (missing or extra ids give 400).
  - Removing a colour stays the editor's job (remove its variants); sync then drops it.
  - The colour family list itself is fixed (`ColorFamily` enum). Adding a family needs a code change, so tell the owner if they ask.
- **Pieces to reuse:**
  - Descriptions: `VariantPlanner.details`.
  - Photos: `VariantPhotoPromptBuilder`. Add a "change only the printed size to X" prompt for the label-text step.
  - Gemini and storage: `GeminiImageClient.edit/download`, `GeminiTextClient.ask` (accepts a photo), `CloudinaryUploader.upload`, `WatermarkService.stamp`.
  - Opening stock: `VariantStockService.addOnHand(productId, variantId, qty, unitCost, "Opening stock", allowUnknownCost=true, staff)`. It refuses kits; the job's staff user = `created_by`.
  - SKUs: `ProductService.nextSku`.
  - Live updates: publish `DataChangedEvent` with a new staff-only topic `VARIANT_JOBS` (add it to `Topic` and to `isPublic()`'s exclusions). Listeners use `fallbackExecution = true`, so publishing from the worker thread works.
  - `@EnableScheduling` is already on. The test profile pushes sweepers' initial delays out an hour; do the same for the job poller and call it directly in tests.
- **Local tests:** run with `DB_PASSWORD=janet-marketing` (see the HANDOFF). The full suite was 964 after Phase 2.

## 4. Phases

Each phase ends with:
- tests green (the full backend suite and the frontend typecheck/tests)
- a HANDOFF entry in each repo touched
- a commit (no push)

**Deploy order is always backend first.**

### Phase 1: Order lines remember what was bought (backend; small, ship first)

- V62 part A: the `customer_order_item` snapshot columns.
- `CheckoutService`: fill the snapshots for every line, including kit components.
- DTOs, emails and hand-off texts: snapshot first, then live.
- **Backfill:** fill snapshots for existing orders from the current variant (best available). Run it in the migration as `UPDATE … FROM product_variant`.
- **Tests:**
  - Rename a variant after checkout → the order and the email still show the old name, colour, size and SKU.
  - A kit order keeps its component snapshots.
  - Old orders with null snapshots fall back.
- Frontend:
  - `account.orders.$orderNo.tsx`, `orders.$orderNo.tsx` and `admin.orders.tsx` show the tags "Colour: X" (with a dot), "Size: Y" and "SKU Z" (mockup tab 3).
  - Fields are optional, so nothing breaks on the old backend.

### Phase 2: Options model + catalogue fields (backend)

- V62 part B: `product_option_value`, plus `product_variant.color_family` and `size_position`.
- **Backfill:**
  - For variants with `option_color` / `option_size` (generated ones): create option rows, positions in `sort_order` order, family from `swatch_hex`.
  - Others stay as they are.
- `ColorFamily` enum + `ColorFamilies.fromHex(hex)` (hue buckets; unit-tested on the example swatches in the mockup).
- Card/detail DTO fields from 3.6. The product editor's save (`SaveProductRequest` / `ProductService`) accepts colour/size/swatch/family per variant and keeps the option rows in sync.
- **Tests:**
  - The DTOs carry the new fields.
  - Older products without options still return siblings.
  - The editor round-trips colour/size.

### Phase 3: Job engine + wizard API (backend; the largest)

- V63: `variant_job`, `variant_job_item`.
- `variantjobs` package:
  - `VariantJobController`
  - `VariantJobService` (preview/validate/create/publish/retry/cancel)
  - `VariantJobValidator` (pure, heavily unit-tested)
  - `VariantJobPoller` (claim with SKIP LOCKED)
  - `VariantJobWorker` (the steps)
  - `GeminiLimiter` (shared)
  - `SwatchSuggester`
  - `AttentionService` + `AttentionController`
- Reuse `VariantPlanner` (descriptions), `VariantPhotoPromptBuilder`, `CloudinaryUploader`, `WatermarkService`, and the photo studio's draft recording for approve-first.
- `DataChangedEvent.Topic.VARIANT_JOBS` (staff-only: add it to `isPublic()`'s exclusions).
- Config: `app.variant-jobs.workers` (3), `poll-ms` (3000), `stale-heartbeat-minutes` (5), `max-per-job` (150), `gemini.requests-per-minute`.
- **Tests:**
  - **Validator:** every error and warning in 3.4.
  - **Pricing modes:** step ascending/descending, per size, per cell.
  - **Exceptions:** a colour adjustment applies to every size of that colour, a cell override beats a colour adjustment, a size restriction excludes the other colours, changing the rule keeps the exceptions, `cells[]` disagreeing with `pricing` gives 422, and the summary text lists every exception.
  - **Bulk price endpoint:** set, +/−, % on a colour/size selection; refuses a result ≤ 0.
  - **Engine** (integration tests with stand-ins for Gemini/Cloudinary, like `VariantGenerationIntegrationTest`):
    - two jobs run at once
    - a quota error pauses and then resumes on its own after `next_attempt_at`
    - a stale heartbeat is reclaimed (simulates a restart)
    - re-running doesn't duplicate (matched on colour+size)
    - excluded cells aren't made
    - opening stock is posted
    - approve-first keeps things hidden until publish
    - a failed photo shows in attention and retry clears it
    - cancel stops between items
  - **Photos:**
    - no label/size photo = one Gemini call per colour, with copies for the sizes
    - label text = an extra edit per variant
    - size photo = recoloured from that photo
- Once this is in, delete or hide the 409 "a run is already in progress" path for variant generation.

### Phase 4: Admin wizard, jobs tray, needs attention (frontend)

- **`src/components/admin/variants/VariantWizard.tsx`** replaces `GenerateVariantsDialog` on the product's "Variants" button. Four steps:
  1. **What varies:** the two checkboxes.
     - A colour list with chips, a swatch picker and family select. Swatches are prefilled from `/suggest-swatches` (debounced).
     - A size list in order, drag to reorder, each with optional label text and an optional photo (upload through the existing image upload path).
     - "Every size comes in every colour". When unticked, a size × colour checkbox grid with per-row and per-column all/none.
     - "This product itself is: colour __ / size __".
  2. **Prices & stock:**
     - The mode picker: same / step up / step down / per size / per cell.
     - Then the full editable grid (sizes as rows, colours as columns), with price and opening stock per cell and an optional cost.
     - Cells changed by hand are highlighted.
     - On a phone the grid scrolls inside its own box.
     - **Exceptions, without leaving the rule:**
       - **Colour header menu** → "This colour costs more/less" → an amount (+150 / −100) applied to every size of that colour.
       - **Size row menu** → "Only some colours come in this size" → tick those colours. This is the same as unticking the others, as one action.
       - **Click any cell** → type its own price → it becomes a cell override.
     - The rule plus the exceptions recompute the grid live. Changing the rule later (e.g. the step amount) keeps the exceptions.
     - An **"Exceptions (n)" list** under the grid reads in plain words ("Gold Shimmer: +KSh 150 in every size", "Ruby Red / Travel 1g: KSh 400", "Travel 1g: only Ruby Red, Nude Beige"). Each one has ✕ to remove it.
     - **Cell colours:** cells that differ from the rule are tinted, and excluded cells are greyed with "not made".
  3. **Check:** calls `/preview`.
     - Red errors block and yellow warnings list.
     - "Already exists, will be skipped".
     - The summary line: "Makes N variants · N photos · about M minutes" and the approve-first checkbox (off by default).
  4. **Generate:** posts the job, closes the dialog, shows a toast "Generating N variants for {product}. Track it in Jobs."
- **Jobs tray** (in `AdminShell` header) with a "Jobs" pill and a count of active jobs. The panel looks like mockup tab 4:
  - the step chips (✓ Variants created → Names → Descriptions → Colour dots → Photos n/N) and a progress bar
  - a thumbnail strip filling in as photos arrive
  - live/hidden state, who started it and when
  - Cancel / Retry / Publish where relevant
  - Also a full page `admin.jobs.tsx`.
- **Needs attention:**
  - A panel at the top of `admin.index.tsx` plus a header pill with the count (mockup tab 4).
  - Buttons call the API or open the right screen (photo manager on that variant, stock grid for those variants, the job's review).
- **A toast on job completion** for any open admin screen, via the `VARIANT_JOBS` live topic.
- **Product editor:**
  - Each variant row shows and edits colour, swatch, family and size.
  - "Add colours or sizes" opens the wizard prefilled with the existing options **and the stored pricing rule + exceptions**. Only new cells are created; existing variants keep their prices unless staff tick "also update existing prices".
  - **Changing prices after generation:**
    - Edit one variant's price inline.
    - Or select variants by colour and/or size (e.g. "all Gold Shimmer", "all 100ml") → set price, or adjust by +/− amount or %.
    - This needs a small bulk endpoint `PATCH /products/{id}/variants/prices` `{variantIds[], setPrice? | adjustBy? | adjustPercent?}`, validated like the wizard (no price ≤ 0). Add it in Phase 3.
- **Stock grid:** a small "receive stock" dialog for a list of variants (used by the attention action). It posts `/stock/receive` with lines.
- Verify in the browser preview against the dev backend:
  - a 6 × 3 product with two missing cells and step pricing
  - two jobs at once
  - a forced photo failure, then retry from Needs attention

### Phase 5: Storefront picker, cards and filters (frontend)

- **`types.ts` / `catalogApi.ts`:** map `optionColor`, `optionSize`, `colorFamily`, `sizePosition` and `brandName` (all optional).
- **Shop cards** (mockup tab 1):
  - The product name, then an option line with the colour dot and "Ruby Red / 50ml".
  - Price (+ old price), wholesale line.
  - Up to 5 dots for the same product's **other colours in the same size** plus "+N". Clicking a dot opens that variant.
  - Each variant stays its own card.
- **Filters in `shop.tsx`:**
  - colour family chips with dots (only families present in the current category)
  - size chips (sizes present in the current category)
  - brand checkboxes
  - min/max price inputs (replacing or alongside the slider)
  - in stock, on deal, wholesale available
  - sort: Recommended / Newest / Price low–high / high–low / Top rated
  - active-filter chips with ✕ and "Clear all"
- Keep filters in the URL search params so a filtered view can be shared. The mobile filter drawer keeps working.
- `lib/search.ts` also matches colour, family and size.
- **Product page** (`product/$id.tsx`, mockup tab 2):
  - Replace the flat sibling list with a **colour row (swatches) and then a size row (buttons with price under each)**, using `detail.colors` / `detail.sizes`.
  - A size not made in the chosen colour is disabled with "Not made in X". A sold-out one is disabled with "Sold out".
  - Picking a colour whose current size doesn't exist switches to the nearest available size and shows a one-line note.
  - **Price differences show where the customer chooses:**
    - When a colour's price in the selected size differs from the usual price for that size, a small "+150" / "−100" sits under its dot, and the colour label reads "Gold Shimmer · KSh +150 in this size".
    - "Usual" means the most common **regular** price among that size's colours. Deals don't count, because they show as deals.
    - Size buttons always show their own price.
  - Every choice navigates (replace) to that variant's id, so the URL, photo, name, price, SKU, stock and add-to-cart all follow.
  - Thumbnails show the same colour's other sizes.
  - Products with one axis show only that row. Older products without colour/size data fall back to the current chip list.
- **Cart drawer, checkout and order confirmation** show the Colour / Size / SKU tags.
- **Prerender** (`scripts/prerender.mjs`): each variant page's `<title>` / meta use the full variant name. Check that `seoLib.test.mjs` still passes.
- Verify at phone width (375px) and desktop, light and dark.

### Phase 6: Tidy existing products (frontend + small backend)

- Old variants made by hand have only a name like "Gel polish – Red".
- **Add an admin screen "Tidy variant options"** listing products whose variants have no colour or size.
  - It suggests colour/size per variant, parsed from the name, with a swatch from `/suggest-swatches`.
  - Staff confirm in a table, and saving fills the option rows and fields.
- Add an attention item "N products have variants without colour/size" with the action "Tidy". Severity INFO, so it doesn't count in the badge.

---

## 5. Done means

- **The wizard:** a product with 10 colours × 10 sizes (2 cells unticked), step pricing and opening stock goes from wizard to Generate in under 3 minutes of staff time. The other session should time it in the preview.
- **Concurrency:** two jobs run at once and a third queues. Restarting the backend mid-job resumes it, and the quota pause resumes on its own.
- **Photos and attention:** every made variant has its own photo or appears in Needs attention.
- **Exceptions:** a product with one colour +150 in every size and one size in only two colours is entered as: a rule + one colour adjustment + one size restriction. That's no per-cell typing, and the check step names both exceptions.
- **The shop:**
  - Variant cards, colour-family / size / brand / price / stock / deal / wholesale filters, URL-shareable.
  - The product page picker matches the mockup and can't select a combination that doesn't exist.
- **Orders:** staff see exactly what the customer saw (name, dot, colour, size, SKU, price), and renaming the variant afterwards doesn't change it.
- **Compatibility:** an old frontend on the new backend works, and a new frontend on the old backend degrades without errors.

## 6. Assumptions to confirm with the owner when reached

- **Colour family list** (3.1): adjust if their shop uses others.
- **Cost:** opening stock with no cost is allowed but flagged (`allowUnknownCost`), since the valuation report needs a cost eventually.
- **Concurrency:** 3 concurrent workers; the Gemini requests-per-minute value depends on their plan, so set it from Settings → Integrations if they have it.
- **The AI can't truly resize a product.** A visible physical size difference needs the staff's size photo; label text only changes the printed size.
