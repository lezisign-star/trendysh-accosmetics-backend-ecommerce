# Nairobi Beauty Hub / Trendysh Accosmetic Backend — Handoff

## 2026-10-02 (even later) — customer returns: `POST /orders/{id}/returns`

`mvn -q compile` clean; V45 applies cleanly on the dev DB (`returned_qty` added to `customer_order_item`,
default 0). **Verified live**: posted a real return on a DISPATCHED order (SKU-001365, qty 1, restock) via the
new admin panel. The ledger shows exactly the expected double-entry (`CR-20261002-000001`): SHOP +1 at **KSh
100** (the variant's original sale cost, not whatever today's average happened to be), CUSTOMER -1; on-hand
went 9 → 10. The order's item now reports `returnedQty: 1` and the "Record return" action correctly disappears
once nothing is left to return.

- **What existed already**: `StockDocumentType.CUSTOMER_RETURN`/`SUPPLIER_RETURN` enum values, unused by any
  service. `StockPostingService.reverse()` deliberately refuses to reverse a `SALE_ONLINE` document (see its
  javadoc: "a return can be damaged-not-restocked; a plain reversal can't distinguish that") — a return needed
  its own posting path, not a generic undo.
- **New**: `OrderReturnService` (package `order.service`) builds a fresh `CUSTOMER_RETURN` document per return —
  one line per returned order item, `CUSTOMER -> SHOP` (restock) or `CUSTOMER -> ADJUSTMENT` (damaged, per-line
  `restock` flag), valued at `CustomerOrderItem.unitCostAtSale` (the cost snapshot `OrderFulfilmentService.fulfil`
  already captures at dispatch) rather than today's moving average. Caps each item at `qty - returnedQty`
  outstanding and refuses an item with no reservation (a kit's own summary line never posted its own movement —
  same skip `fulfil` already applies). Only DISPATCHED/DELIVERED orders are returnable (nothing shipped before
  that). `OrderController.postReturn`, `PERM_ORDER_MANAGE_ALL` — same gate as the other post-dispatch actions.
- **`AdminOrderDetailDto.Line`** now carries `itemId` and `returnedQty` so the admin UI can address a specific
  order line and show what's still outstanding.
- **V45**: `customer_order_item.returned_qty NUMERIC(14,3) NOT NULL DEFAULT 0`.

## 2026-10-02 (later) — stock valuation report, first time it's been surfaced anywhere

`mvn -q compile` clean. **Verified live**: `GET /stock/valuation` against the real dev DB returned real variant
rows (on-hand qty, avg cost, stock value) and a grand total (KSh 1,275,690) rendered on the new admin page.

- **What this is**: `StockPostingService.applyMovement` has computed moving-average-cost valuation correctly
  on every `StockBalance` row since migration V30 (`avgCost`/`stockValue`, confirmed against ERPNext's own
  `get_moving_average_values`) — nothing had ever read it back as a report. No new valuation logic; this is
  purely a read path over already-correct data.
- **Backend**: `ValuationLineDto` (new); `StockBalanceRepository.valuationReport`/`totalStockValue` (same
  mock-data gating clause as the admin analytics queries, starts FROM `StockBalance` rather than
  `ProductVariant` — a variant never received has nothing to report, unlike `browseInventory` which must show
  those rows to flag them as below reorder level); `StockAvailabilityService.valuation()` (new `ValuationReport`
  record: paged lines + total); `GET /stock/valuation` on `StockController`, `PERM_STOCK_VIEW` same as the
  other read-only stock endpoints.
- **Frontend**: new `/admin/valuation` route (Inventory group, between Stock levels and Forecast), modeled on
  `admin.stock.tsx`'s list pattern — search box, paged table, a "Total stock value" card.

## 2026-10-02 — demo-data toggle: analytics dashboard was still counting mock orders when off

Found live: the owner asked why the admin dashboard still showed demo-sized revenue/order numbers with the
"Demo data" toggle switched off. `mvn -q compile` clean; **verified live** — turned demo data on (confirmed via
`/admin/settings/demo-data`: 109 products, 28 mock orders), read the Overview dashboard (revenue jumped
226,850 → 376,176.80, orders 251 → 279, low stock 2 → 10), turned it off, and confirmed all four numbers
returned to the exact pre-demo baseline.

- **Root cause**: `CustomerOrderRepository.search()` (the admin order list) already excludes the 28
  `MOCK-`-prefixed synthetic orders once demo data is off, via `(o.isMockData = false OR EXISTS (… mock
  product still published …))` — the same live signal `MockDataSeederService.status()` itself uses for
  "enabled". But `totalRevenue()`, `countByStatus()` (both backing `/admin/analytics/summary`),
  `ProductSaleEventRepository.topSellers()` and `StockBalanceRepository.countBelowReorderLevel()` never had
  this filter — they summed/counted every order and sale event unconditionally, so turning demo data off
  unpublished the mock products but the dashboard's revenue, order count, top sellers and low-stock tile kept
  including the orphaned mock data forever.
- **Fix**: added the identical gating clause to all four queries. No entity/DTO/migration change — this was
  purely a missing `WHERE` clause repeated in four places that evidently never got the same fix `search()`
  already had.

## 2026-09-30 (later) — `GET /catalog/variants` now has a stable order

`ProductVariantRepository.searchSellable` had no `ORDER BY`, so its order was whatever Postgres happened to return (and paging through it could
skip or repeat variants). It now orders by `v.createdAt DESC, v.id DESC` — **newest first**, which is also what the shop's "Default sorting" shows,
since the shop applies no sort of its own. This is a deliberate change to what shoppers see first: new products lead the shop and the homepage
"Featured products" instead of arriving on the last page. The frontend loader fetches the catalogue in 500-card chunks and depends on a stable order
once a catalogue passes 500. `CatalogVariantCardsIntegrationTest` walks the catalogue two at a time and asserts every variant appears exactly once,
newest first.

## 2026-09-30 — demo-data toggle, flash deals, staff role guards, stock alerts + forecast (V42, V43)

`main` auto-deploys; **deploy this before the frontend** (new endpoints and card fields). V42/V43 are additive and are proven on
legacy-shaped rows by `MigrationUpgradePathTest`. Full suite 513 green on the combined tree, plus 2 added upgrade-path tests.

- **Publish no longer bumps `updatedAt`** (`ProductService.setPublished` -> `ProductRepository.updatePublishedState`; new `JpaAuditingConfig`
  truncates audit timestamps to microseconds app-wide, wired via `@EnableJpaAuditing(dateTimeProviderRef=...)`). Fixes a false "someone else
  changed this product" 409 when saving right after publishing. Regression test in `ProductSaveIntegrationTest`. (Built by another session.)
- **Storefront card + search:** `CatalogVariantCardDto` gained `availableQty`, `sku`, `description`, `siblingCount`; `searchSellable` (`q`) now
  matches description, SKU, category/parent category and a kit's profession label.
- **Developer-only demo data (V42):** `is_mock_data` on product/category/profession/customer_order. `MockDataSeederService` generates ~109
  products (templates in `CategoryTemplates`), 5 profession kits, fake reviews/sales (backed by fake `customer_order` rows), flash deals
  (refreshed on every enable) and a few fast-selling low-stock products. Toggle = bulk flip of `is_published` on tagged rows, so no storefront
  query changed; re-enable restores the same rows. `POST/GET /api/v1/admin/mock-data/{enable,disable,status}` is `hasRole('DEVELOPER')`, NOT a
  permission (SUPER_ADMIN holds the same permissions). Images are Picsum placeholders (`fetchStockPhotoUrl`) — Unsplash's keyword source is dead.
- **Flash deals from the admin (`FlashDealBulkService`, `POST /products/flash-deal[/end]`):** "N% off until <time>" on every sellable variant of
  the chosen products, whole shillings, kits skipped.
- **Staff role guards (`StaffManagementService`):** the DEVELOPER role is hidden from and unassignable by non-developers; a non-developer cannot
  change/disable a developer account; nobody changes their own role; the last active SUPER_ADMIN cannot be demoted or disabled.
  `StaffUserSeeder` also renames the developer account from the old "Owner Admin" placeholder to "Developer Account".
- **Stock alerts + forecast (V43, package `stock.insights`):** `product_variant.reorder_level` is now settable (`PUT /stock/insights/variants/{id}/reorder-level`,
  it had no writer before). `StockForecastService` = 30 days of `product_sale_event` vs `StockAvailabilityService` + open PO lines -> days left,
  runs-out date, restock-for-a-month, suggested reorder level (`ForecastMath`). `LowStockAlertListener` (async, after commit; `AsyncConfig`
  enables `@Async`) emails on `LowStockThresholdCrossedEvent`; `StockDigestScheduler` sends a 07:00 Africa/Nairobi digest, once a day
  (`stock_alert_settings.last_digest_on`). Recipients: `staff_user.receives_stock_alerts`, default TRUE for everyone (an admin edits it in
  Settings). Demo products are excluded from every email. **Expect the first digest after deploy to email all staff (Brevo is live).**
- **Not verified:** none of the new admin screens has been click-tested (admin sign-in is not scriptable from the assistant). Known limits: no digest
  catch-up if the app is down at 07:00; days out of stock understate demand.

## 2026-09-29 — checkout re-prices on every read, live order/payment updates, one more lock-order fix

Built on top of the range below (still `main`, no new migration). Backend + frontend both touched; see the frontend HANDOFF for its half.

- **Checkout re-prices every basket read** (`CartService.refreshPrices`/`refreshPricesForCheckout`): a deal, campaign or plain price
  change is picked up the moment the cart is next read, never from a cached snapshot. A line whose product can no longer be sold is
  flagged `available: false` and blocks checkout; `CheckoutRequest` carries `expectedSubtotal`/`expectedDeliveryFee` — never used as the
  charge, only to refuse with 409 `PRICE_CHANGED` (`CodedException`/`PriceChangedException`) when they disagree with what the server
  is about to charge. `CartRepricingIntegrationTest` and a `TumaBodaIntegrationTest` case cover it. **Still uncommitted.**
- **Live updates for orders and payments**, the event-driven way (mirrors momentspackaging.com's design): `live/LiveStreams` is an
  in-memory `SseEmitter` registry — one broadcast list for staff, one list per order number for customers — written to from its own
  executor (`app.live.sync` for tests), never the request thread. `live/LiveUpdateListener` turns the EXISTING domain events
  (`OrderPlacedEvent`, `PaymentConfirmedEvent`, the order-lifecycle events, the two new ones below) into a staff broadcast
  (`AdminEvent`: type + order number + amount, nothing else) and a bare "something about this order changed" push to that order's own
  watchers — the customer stream never carries the actual data, only the hint to re-fetch. New events: `PaymentClaimSubmittedEvent`
  (from `PaymentClaimService.submit`), `BankCreditRecordedEvent` (from `BankCreditService.record`), `TumaBodaDeliveryUpdatedEvent`
  (booking outcome and webhook status changes). Endpoints: `GET /api/v1/admin/events` (staff, `PERM_ORDER_VIEW`/`PERM_ORDER_MANAGE_ALL`/
  `PERM_PAYMENT_MANAGE`) and `GET /api/v1/orders/track/{orderNo}/events` (public, 404 on an unknown order — reuses the existing
  `/orders/track/**` permitAll rule). `LiveStreamsTest`, `LiveUpdateListenerTest`, `LiveEventStreamIntegrationTest` (the last one is the
  real end-to-end proof: places an order and confirms a payment through the actual endpoints/services and asserts on a
  `@MockitoSpyBean`-wrapped `LiveStreams`). **Still uncommitted.**
- **One more lock-order bug, found chasing the same class of issue as the second audit:** `BankCreditService.tryAutoMatch` and
  `.allocate` read the payment record UNLOCKED before deciding anything from it (status, amount), then handed it to
  `confirmSuccess`/`confirmAndMatch` — the exact "stale object under a later lock" trap already fixed elsewhere (see `PaymentService
  .lockOrderOf`'s javadoc): if a claim approval, a Co-op callback or another credit's auto-match confirmed that SAME payment in the
  gap between the unlocked read and the eventual lock, this code could see a stale INITIATED and try to confirm an order a moment
  after it was already, correctly, paid another way — matching a bank credit to an order without ever noticing the duplicate. Fixed
  the same way as the second audit's payment-claim fix: resolve the id with a scalar query, lock FIRST, decide after. Pinned by
  `BankCreditLockOrderTest` (fails without the fix — confirmed by reverting it and re-running). **Still uncommitted.**
- **A second independent Opus audit of the `c0ff13e` fix commit** (webhook cap / quote limiter / lock order / scheduler pool) found
  no blockers. It also flagged, all LOW and all pre-existing (not from that commit, not fixed here): the TumaBoda retry job can loop
  forever on ONE order (never blocking others) if `TumaBodaBookingService.record` itself throws before committing — a data-shape edge
  case, and the feature is off in production regardless; the booking listener and retry job can race and book the same order at once
  if the executor queue backs up past 3 minutes (harmless — TumaBoda's reference makes a repeat come back as the same delivery, itself
  untested against the real API); a TumaBoda quote-limiter BUSY response surfaces to the shopper as "can't deliver to that address"
  instead of "try again"; `PaymentClaimService.holdItemsForReview` extends stock holds in item order, not variant-id order (a
  pre-existing, low-probability deadlock path with a checkout of the same variants). None fixed; noted for whoever picks up P3/Phase 3.

## 2026-09-27/28 — inventory redesign Phases 0-2, checkout fixes, guest tracking, Paybill settings, TumaBoda API (V29-V38)

Built 2026-09-27/28; 401 tests on a fresh DB in a clean checkout of the pushed commit (one known-flaky test, see below), and two
independent Opus audits of the range (the first found a boot-blocking migration; both sets of findings are fixed, see "Lessons").
`main` auto-deploys to Railway. Migrations V29-V38 are all new to production; read `MigrationUpgradePathTest` before adding another.

- **Inventory redesign (Odoo/ERPNext-grounded), Phases 0-2 done:** `@Version` + deterministic lock order on stock rows,
  read-path ArchUnit rule (no `@Query` outside `stock` may touch StockBalance/StockReservation/StockLedgerEntry), movement history,
  Holds browse, document reversal (`StockPostingService.reverse`, locked), structured write-offs, stock counts. Phase 3 (valuation)
  is next; it touches `applyMovement` and must land before customer returns.
- **Checkout:** delivery methods BODA/MATATU/COURIER, transporter assignment (V35; courier orders can't be dispatched without one),
  late payment after the hold lapsed re-takes the stock or refuses with a clear message, unpaid orders now EXPIRE
  (`OrderExpirySweeper`), per-contact open-order cap (3), cart quantity checks.
- **Guest order lookup:** an order number alone gives the status only; an email gets an emailed 6-digit code (hashed, single use,
  5 guesses, guesses are counted under a row lock) then a 30-minute `X-Track-Token` session (V36).
- **Paybill:** number, merchant name and the account-number template (`<name> <phone> <order> <ref>`, default `<name>`) are admin
  settings (V37); staff confirm from Orders or Payments (both update the same payment). A blank account falls back to the order number.
- **TumaBoda through the partner API (V38), OFF until `app.tumaboda.api-key` is set** (env `APP_TUMABODA_API_KEY`;
  `APP_TUMABODA_BASE_URL` defaults to TumaBoda's SANDBOX, production is `https://api.tumaboda.co.ke/api`;
  `APP_TUMABODA_WEBHOOK_SECRET`). Checkout quotes the drop-off server-side and adds the fee to the total; a rider is booked when
  staff mark the order READY (after commit, own executor, retried 3/10/30/60 min, then handed to staff); the signed webhook
  (`POST /api/v1/tumaboda/webhook`, header `X-Tuma-Signature: t=..,v1=..`, HMAC-SHA256 over `t.rawBody`) and a reconciliation poll
  move the order (rider collects = DISPATCHED, delivered = DELIVERED). **Never run against real TumaBoda:** request/response shapes
  come from Moments' working integration and are tested only against a local fake (`FakeTumaBodaServer`). Trendysh needs its own
  partner account (key, webhook secret, pickup location registered on their side). Package size/vehicle is a placeholder heuristic
  on basket value (5,000 = large, 20,000 = van; `app.tumaboda.*-threshold-kes`). Public endpoints: `/api/v1/public/tumaboda/{status,maps/*,quote}`.
- **Lessons (2026-09-28 audit):** V33 would have aborted Flyway on any database holding a VEHICLE order (empty-DB tests can't see it);
  fixed, and `MigrationUpgradePathTest` now migrates a scratch schema to V28, seeds legacy-shaped rows, then applies the rest.
  One-time codes (lookup, shopper reset, staff reset) count guesses under a `PESSIMISTIC_WRITE` lock; a payment confirmed or failed
  late locks payment, then order, then stock; the reservation sweeper re-checks expiry under the lock; emails go through
  `emailTaskExecutor` (sync in tests via `app.email.sync-send`).
- **Second audit fixes:** the public TumaBoda webhook reads no body without a configured secret and a signature and refuses more than
  64 KB (413); a customer's "I've paid" claim locks the payment row first, like `confirmSuccess` (a test fails without it); at most
  4 TumaBoda price checks run at once (each holds a DB connection while it waits; the rest get a retryable BUSY) and the fee is rounded
  UP to a whole shilling (M-PESA takes no cents); `spring.task.scheduling.pool.size: 4` (Boot's default is ONE thread for every
  `@Scheduled` job); the email pool is 4 threads (a pool only grows past core once its queue is full); marking an API delivery READY
  leaves a 3-minute safety-net retry so a booking lost between commit and call is picked up by the retry job.
- **Rate-limit key:** the TumaBoda public endpoints key their limits on the LAST `X-Forwarded-For` hop (the one Railway's proxy adds).
  Confirm Railway really appends the client address there, and remember shoppers behind one carrier NAT share a key.
- **Known flaky test:** `MerchandisingIntegrationTest.popularAndNewArrivalFlagsRoundTripThroughSaveAndTheCatalogueFilter` fails with a
  409 "Someone else changed this product" in roughly half of full runs and passes alone. It is the whole-second `updatedAt` staleness
  check in `ProductService.saveProduct` and likely a real spurious-409 for staff on their first save after creating a product.
- **Deploy order:** backend first, then the frontend `staging` branch straight away (the new frontend needs the new endpoints; the old
  frontend breaks on the new backend for vehicle checkout, the track page and courier dispatch).
- **First expiry sweep after deploy** marks every historical unpaid order EXPIRED; the awaiting-payment queue ages them from the
  payment window, so they do not flood it.

## Inventory redesign Phase 3f — the old attribute/option editor and single-category pair are gone from the code (2026-09-25, later same day)

The Java-side half of the cleanup V27's own comments already promised ("removed in Phase 3f once
nothing calls the old endpoints any more") — now that Phase 3a-3e have been live and re-verified,
and the frontend confirmed to call none of it.

`V28__drop_attributes_and_old_category_columns.sql` drops `product_attribute`,
`product_attribute_value`, `variant_attribute_value`, and `product.category_id`/`subcategory_id`.
The founder applied that same DDL directly against production first (via the Railway Postgres
console), before the migration file existed as a tracked migration — so the file uses
`DROP TABLE IF EXISTS` / `DROP COLUMN IF EXISTS` throughout, making it a real drop on dev/test
(where the columns/tables are still present) and a no-op on production (already gone), so
`flyway_schema_history` ends up consistent — "version 28 applied" — in every environment either way.
Verified against a disposable copy of `nairobi_beauty_hub_dev`: Flyway migrated 27→28, the app
booted clean, Hibernate validated the now-columnless `product` table, and `/catalog/variants` /
`/catalog/categories` both still answered 200.

Deleted outright: `entity/ProductAttribute.java`, `ProductAttributeValue.java`,
`VariantAttributeValue.java`; `repository/ProductAttributeRepository.java`,
`ProductAttributeValueRepository.java`, `VariantAttributeValueRepository.java`;
`controller/AttributeController.java`; `dto/ProductAttributeDto.java`. Also deleted the old
single-category-pair create/update surface entirely, since nothing called it any more (confirmed
by grepping the frontend, which finished migrating off it in Phase 3c/3e): `dto/CreateProductRequest.java`,
`UpdateProductRequest.java`, `UpdateVariantRequest.java`, and `ProductController`'s
`POST /products`, `PATCH /products/{id}`, `POST /products/{id}/variants`,
`PATCH /products/{id}/variants/{variantId}` — along with `ProductService`'s `createProduct`,
`updateProduct`, `addVariant`, `updateVariant`, `createVariant`, and `applyCategories` methods.
Also removed `VariantBarcodeRepository` from `ProductService`'s constructor — barcode-linking only
ever existed on the now-deleted old `createVariant`, so the field had gone completely unused; the
`VariantBarcode` entity/table/repository themselves are untouched, ready if barcode support comes
back through the new editor later.

The old public storefront surface (`CatalogProductSummaryDto`, `CatalogProductDetailDto`,
`CatalogVariantDto`, and `CatalogController`'s `GET /catalog/products`, `GET /catalog/products/{slug}`,
`GET /catalog/products/{id}/related`) is also gone — Phase 3e confirmed the frontend calls none of
it any more (`/catalog/variants*` replaced all of it). `ProductVariantDto` lost its `attributes`
field and the 3-arg `from(...)` overload that populated it; every caller already only used the
1-arg or 2-arg form except the two now-deleted admin endpoints.

Test suite: `VariantSkuAndAttributesIntegrationTest.java` deleted outright (its 4 tests all
exercised the now-gone `POST /products` + `/attributes` endpoints) — its 2 genuinely valuable SKU-
generation assertions (category-prefixed, sequential-and-unique) were ported into
`ProductSaveIntegrationTest.java` against the modern `POST /products/draft`. `CatalogServiceInStockUnitTest.java`
rewritten to call `variantDetail()` instead of the deleted `getDetail()`, same in-stock-rollup
concern. `MerchandisingIntegrationTest.java`'s `newProduct`/`catalogueEntryByName` helpers switched
to the modern endpoints (field names changed: `minPrice`→`price`, `originalMinPrice`→`originalPrice`
on `CatalogVariantCardDto`); its "flags round-trip" test rewritten against `PUT .../full`; its
"partial PATCH preserves omitted description fields" regression test deleted outright — that
concern was specific to the old PATCH endpoint's deliberate merge-only semantics, which don't exist
on the new full-replace `saveFull` endpoint (the one-page editor always sends the complete current
form state, so there's nothing to preserve). `ProductEditorIntegrationTest.java`'s
`aSubcategoryMustBelongToTheChosenCategory` test deleted — its premise (cross-validating a
category/subcategory pair) doesn't exist in the new flat `categoryIds` model, by design; its one
still-valid assertion (choosing only a subcategory files the product under its parent too) kept as
its own smaller test. `ProductImageGalleryUnitTest.java` and `PurchaseOrderIntegrationTest.java`
adjusted for the new `ProductService` constructor shape and draft-endpoint test fixtures
respectively.

Verified: full `mvn test` green — 42 test classes, zero failures, run against the local
`nairobi_beauty_hub_test` database (schema still at V27; unaffected since none of the removed code
was ever mapped to the columns/tables the pending V28 will drop).

## GlobalExceptionHandler: an unmapped route now returns 404, not 500 (2026-09-25, later same day)

Found live right after Phase 3b's deploy: a stale Railway build made every request to the two new
`/catalog/variants*` endpoints fall through to Spring's static-resource handler (the route wasn't
in that build yet), which threw `NoResourceFoundException` — and `GlobalExceptionHandler` had no
handler for that type, so it fell to the `Exception.class` catch-all and came back as a 500 "An
unexpected error occurred". Restarting the deployment picked up the real build and fixed the
immediate symptom, but the handler gap itself was real and independent of that stale-build
coincidence: any future typo'd or removed route under a public prefix (`/catalog/**`, etc.) would
hit the same catch-all and look like a server error instead of a plain 404.

Added `@ExceptionHandler(NoResourceFoundException.class)` mapping it to 404 with this API's normal
JSON error shape. Verified live: `GET /api/v1/catalog/this-route-does-not-exist` (a path under the
public prefix, so it reaches the dispatcher instead of being rejected by security first) now
returns `404 {"error":"No such endpoint: ..."}` instead of a 500. Full suite **213/213** unaffected.

## Inventory redesign Phase 3b — storefront API, one card per variant (2026-09-25)

Backend half of the storefront redesign: every SELLABLE VARIANT gets its own catalogue card now,
not one card per product with a shade picker. Purely additive — no migration, no schema change —
so it ships alongside the old `/catalog/products*` endpoints (kept working, removed in Phase 3f)
with zero risk to the current frontend, which hasn't been switched over yet (Phase 3e).

**New public endpoints:**
- `GET /catalog/variants` — the browse grid. Same filters as the old `/catalog/products`
  (`q`, `category`, `deal`, `popular`, `new`), same category-parent-rollup behaviour, but returns
  `CatalogVariantCardDto` (one row per sellable variant) via `ProductVariantRepository.searchSellable`.
- `GET /catalog/variants/{variantId}` — a variant's own product-detail page:
  `CatalogVariantDetailDto` = the card + this variant's own description + the product's shared
  description/ingredients/how-to-use + images (this variant's own photos first, then the
  product's shared ones) + `siblings` (every sellable variant of the same product, including
  itself, in display order — the "also available" strip) + primary-category/parent for
  breadcrumbs.

**The name-hiding rule** (`CatalogVariantCardDto.isMeaninglessName`): `variantName` is null — the
card just shows the product name — when the product has only one sellable variant, or the
variant's own name is blank or the auto-assigned placeholder `"Default"`. Matches the rule
`CheckoutService` already used for order-confirmation text, so the storefront and order history
never disagree about what a single-variant product is "called".

**R2 from the Phase 3 plan, finally closed:** `CatalogService.browse`/`getDetail`/`related` now
all filter to sellable variants only (not deleted, active, priced — `ProductVariant.isSellable()`)
via new `deleted=false AND isActive=true AND sellingPrice IS NOT NULL` repository finders, instead
of the old unfiltered `findByProductId`/`findByProductIdIn`. A variant an admin just soft-deleted
or hid could previously still appear on the storefront read side (checkout's own guard already
closed the *buy* path in Phase 3a — this closes the *see it at all* path). Also switched
`CatalogService` off `StockBalanceRepository` onto `StockAvailabilityService` (the single
"available" definition every other reader already goes through) — closes this class out of the
gap `ProductRepository.searchAdmin`'s old JPQL left in the Phase 1 HANDOFF entry's ArchUnit note.

**Same landmine, caught before shipping this time:** the new `searchSellable` JPQL used
`parent.slug`/`c.parent.slug` navigation — the exact implicit-inner-join trap `ProductRepository`'s
queries already had fixed once in Phase 3a. Written with the explicit `LEFT JOIN c.parent parent`
from the start here, so it never broke in the first place; flagged in this entry only so the
pattern is recorded somewhere obvious for the next query someone adds against `categories`.

**Tests:** `CatalogVariantCardsIntegrationTest` (3 cases — unpublished/hidden/priceless/deleted
variants never appear while their sellable siblings do; a product in two categories is found by
both slugs and their shared parent's slug; the sibling list and name-hiding rule are both
correct), plus `CatalogServiceInStockUnitTest` updated for the `StockAvailabilityService` swap and
the new sellable-filtered finder. Full suite **213/213** green (210 + these 3) on a fresh test
database.

**Verified live** against a real dev boot: `/catalog/variants` returns real cards with correct
images (a variant's own photo, falling back to the product's cover), prices (deals resolve
correctly), and stock status; `/catalog/products` (the old endpoint) still works unchanged;
category-slug and its parent's slug both find the same product; a variant's own detail page
returns its real siblings with correct `siblingCount`; a nonexistent/unsellable variant id 404s.
`GET /admin/stock/consistency-check` stayed `consistent: true` throughout.

**Not yet done, in order:** Phase 3c (the actual one-page editor frontend — nothing in the
frontend uses any of Phase 3a's new endpoints yet), 3d (product list redesign), 3e (storefront
frontend — nothing uses Phase 3b's new endpoints yet either), 3f (drop the attribute tables +
`V28`, only once 3a–3e are live and re-verified).

## Inventory redesign Phase 3a — plain variants, multi-category, one-page editor API (2026-09-24, later same day)

Backend half of the actual UX fix the audit was for (Phase 1/2 were branches; this is the real
"products CRUD is confusing" complaint). Full Opus-authored plan (3a–3f) lives only in this
session's conversation, not written to disk — this entry is the record of what actually shipped.

**Schema (`V27__plain_variants_and_multi_category.sql`, additive only):** new `product_category`
join table (product ↔ category many-to-many, `position` 0 = primary — used for breadcrumbs/SKU
prefix), backfilled from the old `category_id`/`subcategory_id` pair (the more specific one wins).
`product_variant` gained `description` and `sort_order` (backfilled by creation order), and
`selling_price` became nullable — a draft variant can now be saved with no price at all. The old
`product.category_id`/`subcategory_id` columns are untouched (kept for rollback safety, no longer
mapped in code); `V28` in Phase 3f drops them along with the attribute tables once the new frontend
is live and re-verified. Guard re-checks the same subcategory/parent consistency Phase 3.0 already
confirmed clean.

**`ProductVariant.isSellable()`** — deleted=false, active=true, price not null — is now the one
place "can this appear/be sold" is defined; `EffectivePriceResolver`/`VariantDealService` both
throw on a null price rather than silently miscomputing.

**Real pre-existing bug fixed in passing:** `CartService.addItem`/`updateItemQty` never checked
that a variant was active/not-deleted or its product published/not-deleted — a soft-deleted or
hidden variant stayed addable to a cart that already referenced it. Now rejected with "This item is
no longer available", live-verified.

**New one-page-editor API** (`SaveProductRequest`, nothing `@NotBlank` — a draft saves in any
state; publishing only returns `ProductDto.warnings()`, it never blocks, per the founder's "warn,
don't block" rule):
- `POST /products/draft` / `PUT /products/{id}/full` — the single Save. Diffs the variant list
  (matched by id) against what's stored: missing = soft-deleted, no id = created with an
  auto-generated SKU, order in the list becomes `sortOrder`. `expectedUpdatedAt` is an optional
  optimistic-concurrency check — a mismatch is a 409 "Someone else changed this product".
- `POST /products/{id}/duplicate` — an unpublished "Name (copy)" with the same categories,
  variants (new SKUs) and photos.
- `POST /products/bulk` — `PUBLISH`/`UNPUBLISH`/`DELETE`/`RESTORE`/`ADD_CATEGORIES`/
  `REPLACE_CATEGORIES`/`ADJUST_PRICE` (percent, rounds half-up to whole shillings, clears a sale
  that's no longer below the new price, skips priceless variants) across many products at once.
- Photos deliberately stay on the existing dedicated image endpoints, not folded into this
  request — no behaviour change there, kept the diff manageable.
- `CategoryRepository`/`CategoryController` counts now roll a subcategory's products up to its
  parent (a product filed only under a subcategory used to undercount the parent).

**A real bug caught by the test suite, not by inspection:** the new `searchPublished`/`searchAdmin`
JPQL used `c.parent.slug`/`c.parent.id` directly — the exact same "implicit path navigation compiles
to an INNER JOIN" landmine `ProductRepository`'s own comments already warned about for the old
`p.subcategory.slug`. It silently dropped every product with **no category at all** from every
catalogue/admin search. Fixed with an explicit `LEFT JOIN c.parent parent` in all three rewritten
queries (`searchPublished`, `searchAdmin`, `findRelated`) — caught by `MerchandisingIntegrationTest`
(4 failures) before this ever reached dev.

**Verified:** full backend suite **197/197** green on a fresh test database. `V27` dry-run against a
disposable `pg_dump`/`pg_restore` copy of dev first, then applied to real dev **through Flyway**
(never by hand). Live curl end-to-end: draft creation with 2 categories + a priceless variant
(warnings correct: "No photo", "1 variant has no price…", "A variant needs a name"), full save that
publishes despite warnings (succeeds, warnings still returned), a stale `expectedUpdatedAt` (409),
duplicate, the soft-deleted-variant cart rejection above, and all of `ADJUST_PRICE`/`DELETE`/
`RESTORE` via `/products/bulk`. `GET /admin/stock/consistency-check` stayed `consistent: true`
throughout. Test data cleaned up afterward.

**Gap closed same day:** `ProductSaveIntegrationTest` (6 cases: blank draft, full round-trip,
variant removal soft-deletes, stale `expectedUpdatedAt` 409, multi-category rollup, publish-with-
warnings), `ProductBulkActionsIntegrationTest` (5 cases: publish/unpublish, delete/restore,
add-vs-replace categories, price-adjust clearing an invalidated sale, price-adjust skipping a
priceless variant), `ProductDuplicateIntegrationTest` (2 cases) — 13 new tests, all against the
real security chain and database like this file's other integration tests.

**A real bug the new tests caught, already live in production at the time:** the optimistic-
concurrency check compared `expectedUpdatedAt` to the stored value with exact `Instant.equals()`.
The JVM auditing clock sets `updatedAt` with nanosecond precision, but Postgres's `timestamptz`
only keeps microseconds and the JDBC driver *rounds* (not truncates) to the nearest one on write —
a value like `.022999900` can round up to `.023000000` on storage. That meant a solo, uncontested
save had a real chance of getting a spurious 409 "Someone else changed this product" on its very
next edit. Fixed by truncating both sides to whole seconds before comparing — a same-second
double-save by two different staff going undetected is an acceptable trade-off for a low-traffic
admin tool; a spurious block on ordinary solo saves was not. **This fix needs its own push and
production verification** (see below) — it was live and wrong from the moment 3a first deployed
until this fix.

Full suite now **210/210** green (197 + these 13) on a fresh test database.

**Not yet done, in order:** Phase 3b (storefront API: one card per variant, sibling
recommendations), 3c (the actual one-page editor frontend), 3d (product list redesign), 3e
(storefront frontend), 3f (drop the attribute tables + `V28`, only once 3a–3e are live and
re-verified).

## Inventory redesign Phase 3.0 — plain-variant/multi-category pre-flight (2026-09-24, later same day)

Read-only pre-flight for the next redesign phase (plain variants — name/photo/price/description
only, no attribute concept — and products in several categories; full Opus-authored plan covers
3a–3f, not written to disk, see this session). Ran on both dev and production, same connection
method as the Phase 0 branch-removal pre-flight (production via the Railway public TCP proxy, never
the internal hostname).

**Attribute data — the one real risk the plan flagged — turned out to be a non-issue:** production
has 1 attribute definition, 0 attribute values, 0 variant links. Dev has 1 attribute/2 values/1
variant link, and that one linked variant's name already fully contains its attribute's value
(`ILIKE` check), so nothing meaningful would be lost by dropping the attribute tables later in 3f.

**Other checks, all clean:** no product has a subcategory whose parent disagrees with its category
(the guard the planned `V27` migration will also re-check). Production: 0 products with no category.
Dev: 4 products with no category, 4 variants named blank/`Default` — pre-existing test/seed data,
not a migration blocker; the new editor's warning system (Phase 3a) will just surface these once
built. Scale is tiny either way — production is 2 products/7 variants, dev is 27 products/31
variants — no near-term risk from the storefront's client-side-filtered catalogue (flagged in the
plan as a later follow-up regardless, once variant count grows past ~2000).

**Conclusion: clear to start Phase 3a** (backend: plain-variant model, one-page-editor API,
product↔category many-to-many, bulk actions, duplicate). Phase 3a/3b ship additively (old frontend
keeps working via compatibility fields) — the destructive `V28` cleanup migration only happens in
Phase 3f, after 3a–3e are live and re-verified.

## Inventory redesign Phase 1 — branches eliminated completely, backend (2026-09-24)

**Founder decision, not a refactor of convenience:** after a UX audit found the admin
product/variant/category screens confusing, and a further audit found the branch/
location model genuinely buggy (see below), the founder chose full removal of
branches/locations over hiding them — see the session's Opus audit trail for the
full reasoning. This entry covers the backend half; frontend is next (Phase 2), not
started yet as of this entry.

**What replaced Branch/StockLocation:** a 4-value enum, `stock.entity.StockAccount`
(`SHOP` physical; `SUPPLIER`/`CUSTOMER`/`ADJUSTMENT` virtual, unchanged in meaning
from the old `LocationType`). No table, no rows — the double-entry ledger's
correctness (every movement has two sides; `StockBalanceRebuildService`'s
"everything sums to zero" check) is fully intact, just keyed by account instead of
location. `V26__single_stock_account.sql` (migration order matters — read its own
comments; it merges SALES_FLOOR/BACKSTORE/DAMAGED into SHOP, keeps SUPPLIER/
CUSTOMER/ADJUSTMENT as-is, and has guards that refuse to run if DAMAGED/IN_TRANSIT
hold stock or any TRANSFER document/reservation exists — none did on this project).

**Two real bugs this closes, not just hides:**
1. Checkout's old branch-fallback logic could reserve stock at a different branch
   than `OrderFulfilmentService.fulfil` actually dispatched from — depending on
   timing this either failed dispatch outright or silently corrupted both
   branches' balances. With one account, "reserve here, dispatch from there" is no
   longer expressible — `StockPostingService.post` now hard-rejects (with a unit
   test) any line fulfilling a reservation whose source isn't SHOP, or whose
   variant doesn't match the reservation's.
2. The shop page, add-to-cart and checkout could each compute "in stock"
   differently (shop summed all branches including damaged locations; cart only
   checked the main branch). There is now exactly one definition, in the new
   `stock.service.StockAvailabilityService`, and a new ArchUnit rule in
   `SingleWritePathArchitectureTest` forbids any class outside
   `com.nbh.backend.stock.service` from depending on `StockBalanceRepository` or
   `StockReservationRepository` at all — not just their mutating methods, reads too.
   (Known gap, pre-existing and not closed by this rule: `ProductRepository
   .searchAdmin`'s stock-filter subquery still reaches into `StockBalance` directly
   via JPQL, bypassing the service — it was already doing this before today, just
   against `location` instead of `account`; worth folding into Phase 3's rewrite.)

**Also removed:** `staff_branch_assignment`, `StaffBranchAccessService`, the
`BRANCH_MANAGE`/`STOCK_TRANSFER_INITIATE`/`STOCK_TRANSFER_APPROVE` permissions, the
`TRANSFER` stock-document type and `TRANSFER_OUT` reservation source (designed,
never built), the `BranchController`/Branches admin screen (frontend, Phase 2), and
`FulfilmentType.BRANCH_PICKUP` → renamed `PICKUP`. `BRANCH_MANAGER` role renamed
`MANAGER` (`StaffRoleSeeder` re-syncs by name on boot — the seeder's key had to move
too, or it would have created a stray duplicate `BRANCH_MANAGER` role next to the
renamed `MANAGER` row).

**Verified, not just tested:** the migration was dry-run against a full copy of dev
(`pg_dump`/`pg_restore`) before touching the real database — balances matched the
pre-migration totals exactly, the append-only ledger trigger still rejected a test
UPDATE, and the double-entry sum-to-zero check passed for every variant. Applied to
real dev **through Flyway** (not by hand — see the note below), then live-verified
end to end via curl against the running app: add-to-cart → checkout → confirm
payment → dispatch → mark-delivered (stock moved exactly as expected: SHOP -1,
reservation released, CUSTOMER +1), a `PICKUP` checkout (and confirmed the old
`BRANCH_PICKUP` value is now correctly rejected), a direct receive, a write-off, and
a full purchase-order send→receive cycle. `GET /api/v1/admin/stock/consistency-check`
reported `{"balanceMismatches":[],"doubleEntryViolations":[],"consistent":true}`
after all of that. Backend suite: **197/197** on a fresh test database.

**Pitfall hit and worth recording:** the first attempt applied V26 directly via
`psql -f` against the real dev database to be extra careful/fast — this works, but
Flyway never finds out, so `flyway_schema_history` has no row for version 26 and the
next app boot tries to run V26 again against a database where it's already half
irreversible (tables already dropped) → boot fails. Fixed by restoring dev from the
pre-migration `pg_dump` and letting Flyway apply V26 itself, the normal way. **Never
hand-apply a migration to a database Flyway also manages** — always let Flyway run
it, even in a hurry; dry-run against a disposable restored copy first instead.

**Phase 0 production pre-flight — run 2026-09-24, clean:** connected read-only to the
live Railway Postgres (public TCP proxy, not the internal hostname — that only
resolves inside Railway's network) and ran the same three guard queries V26 itself
re-checks, plus a broader sanity pass, before ever running the migration there:
- DAMAGED/IN_TRANSIT locations holding stock: **0** (2 DAMAGED + 1 IN_TRANSIT
  location row exist, all with `on_hand_qty`/`reserved_qty` = 0).
- `TRANSFER` stock_document rows: **0**. `TRANSFER_OUT` reservations: **0**.
- `customer_order.fulfilment_type` distribution: 1 `DELIVERY_COURIER`, 0
  `BRANCH_PICKUP` — nothing for the rename to touch.
- `staff_branch_assignment`: **0** rows (table drops clean).
- `staff_role` name collision check: exactly one `BRANCH_MANAGER` row, no existing
  `MANAGER` row — the rename in V26 is a plain rename, not a merge.
- Overall production has very little live data yet (1 goods receipt, 1 online sale,
  1 fulfilled reservation, total recorded stock value ≈ 0) — low blast radius even
  before the guards are considered.

All clear. **Safe to push to `main`** as far as this pre-flight is concerned — still
apply the same discipline as the dev run: let Flyway apply V26 on deploy, never by
hand against production.

**Not yet done, in order:**
- Phase 2 (frontend): remove the Branches screen, the branch/location pickers in
  Restock and Purchase Orders, `BRANCH_PICKUP` → `PICKUP` everywhere in the
  frontend, the "Fulfilled from…" branch line in admin order detail. Ships together
  with this backend change — a frontend still sending `BRANCH_PICKUP` or a
  `locationId` will get 400s.
- Phase 3+ (plain variant model, product editor redesign, storefront variant
  cards) — see the full build plan from this session's planning pass for the
  complete phase-by-phase spec; not started.

This commit is **local only as of this entry** — reviewed but not pushed, per the
production pre-flight gap above.

## Bank/paybill/WhatsApp settings become admin-editable, not static config (2026-09-24, later same day)

**Phase G of the admin-dashboard rework** (Phase F was frontend-only — quick-edit camera capture,
no backend change). This is the actual fix for the "payment confusion" flagged at the very start of
the rework: the admin Settings tab's bank details were never real — they wrote to `localStorage` and
had zero effect on what a customer saw at checkout. `BankPaymentSettings` used to read bank name/
paybill/account/WhatsApp straight from `@Value`-bound env vars; it's now backed by a new single-row
`payment_settings` table (migration `V25__payment_settings.sql`, deliberately seeded with **no**
values baked into the migration itself — a real bank account number never belongs in git history).

A new `PaymentSettingsSeeder` (`@PostConstruct`, same "seed once, only if empty" pattern as
`StaffUserSeeder`) copies whatever the live `app.bank.*` env vars hold into that row the first time
the app boots after this deploy — a value-preserving cutover, not a reset. From then on
`BankPaymentSettings`'s bank-detail accessors (`bankName()`/`paybillNumber()`/etc.) read through the
new `PaymentSettingsRepository` instead of the old `@Value` fields; only `paybillAccountTemplate` and
the M-PESA/collection-mode fields stay env-var config (deliberately not admin-editable). New
`AdminSettingsController` (`GET/PUT /api/v1/admin/settings/payment`, gated on the existing
`PERM_SETTINGS_MANAGE`) — a blank field on `PUT` clears that value rather than being rejected, so an
admin can deliberately hide a payment method on the pay page.

**Found and fixed a real shared-test-database bug while wiring this up**: `payment_settings` is one
row shared across every Spring test context in the whole suite (unlike `@TestPropertySource`, which
is scoped per context) — whichever test class's context happened to boot *first* during a full
`mvn test` run silently decided that row's content for every other test, since the seeder is a
one-time no-op after that. `BankPaymentPageIntegrationTest` was flaky against this exact bug (passed
alone, failed in the full suite) until it was changed to write its own `PaymentSettings` row directly
via the repository in `@BeforeEach` instead of relying on `@TestPropertySource` + the seeder — also a
more accurate model of production now, where these values live in the DB, not env vars.

New `AdminSettingsIntegrationTest` (update-then-read round-trip, blank clears a field, non-
SUPER_ADMIN role gets 403). Full suite green (199 tests). Verified live: booted a second instance
against the dev DB, confirmed `V25` applies cleanly and the seeder fires exactly once.

## Branch-aware checkout, real branch CRUD, order branch visibility (2026-09-24, later same day)

**Phase E of the admin-dashboard rework**, prompted by the owner watching a screenshot of stray
test-data categories and asking for real multi-branch inventory management. Two parts:

1. **Checkout now falls back across branches.** Every online order used to silently reserve stock
   from one hardcoded branch (`branchRepository.findByIsDefaultOnlineFulfilmentTrue()`) — an
   out-of-stock line there failed checkout even if another branch had it. `CheckoutService` now
   tries the main branch first per line, and only reaches into other active branches (deterministic
   order, by code) when the main one is short — the owner's explicit rule, not a full nearest/
   cheapest allocator. The schema already supported this (`StockReservation.location` per order
   line already existed); only the checkout loop's location resolution changed — no migration.
   **Customer-facing errors were cleaned up in the same change**: the raw
   `InsufficientAvailabilityException` message includes an internal location code
   (`"...at BR-A/SALES_FLOOR..."`) — `CheckoutService` now catches it and rethrows a message naming
   only the product, never any branch/location, and only when NO branch has enough (the owner was
   explicit: customers must never see a "branch is short" state, only a genuine "out of stock").
   Confirmed the storefront's existing `inStock` flag (`CatalogService.inStockVariantIds`) already
   aggregates across all branches, not just the main one — no change needed there.

2. **Branches went from create-only to real CRUD**, plus visibility. `BranchController` gained
   `PATCH /{id}` (name/address/city/phone/email/active/**main-branch flag** — setting the latter
   atomically clears whichever branch currently holds it, matching the DB's existing partial-unique-
   index guarantee). `BranchDto` widened from `{id,code,name,city,isActive,isDefaultOnlineFulfilment}`
   to also include `addressLine1/2`/`phone`/`email` (all were already on the entity, just never
   exposed). `AdminOrderDetailDto` now carries the order's main branch and, per line, which branch
   its stock actually came from (derived from the existing reservation/location chain — nothing new
   stored) so staff can see at a glance which branch(es) to coordinate with for dispatch.

New `CheckoutBranchFallbackIntegrationTest` (3 tests, real DB, two real branches): main branch
covers a line it can handle alone, another branch covers a line the main one can't, and a
genuinely-everywhere-short line fails with the clean customer message (asserted to *not* contain any
branch/location code). `CheckoutServiceUnitTest` updated for the new constructor dependency and the
new customer-facing message shape. Full suite green (196 tests).

## Purchase orders (LPOs): suppliers, lifecycle, real stock on receipt (2026-09-24, later same day)

**Phase D of the admin-dashboard rework** — the one genuinely large new backend subsystem in the
whole plan (everything else so far reused something that already existed). New `purchasing` package:
`Supplier`, `PurchaseOrder`, `PurchaseOrderLine` (migration `V24__purchasing.sql`). A PO moves
`DRAFT -> SENT -> PARTIALLY_RECEIVED/RECEIVED`, or `CANCELLED` (only while nothing's been received
yet).

**Receiving a PO posts a real `GOODS_RECEIPT` `StockDocument` through the existing
`StockPostingService`** — `PurchaseOrderService.receive()` builds the document/lines exactly like
`StockController.receive()` already does for a plain restock, tagging it via the document's
`referenceType`/`referenceId` columns (found already sitting on `StockDocument`, indexed, completely
unused by any code path until now). This service never writes a stock quantity itself —
`SingleWritePathArchitectureTest` passes unmodified, which is the mechanical proof it didn't sneak
around the single write path. PO numbers (`PO-20260924-000001`) reuse the same atomic per-key counter
table `StockDocumentNoGenerator` already uses for `GRN-`/`ADJ-` etc., just with a different key prefix
— no new sequence/table needed there either. The "which location received this" picker uses the
already-existing, previously-unused-by-any-frontend `GET /branches/{id}/locations` endpoint.

New `/api/v1/admin/suppliers` and `/api/v1/admin/purchase-orders` controllers, gated by the existing
`STOCK_RECEIVE`/`STOCK_VIEW` permissions — already correctly scoped to Branch Manager, Stock
Controller, Super Admin and Developer by the existing role seeder, so no role changes were needed.

New `PurchaseOrderIntegrationTest`: draft creation with a real sequential PO number, the
can't-receive-a-draft guard, a genuine two-part partial receipt verified against `/stock/balance`
(not just PO bookkeeping), the over-receive guard, and the can't-cancel-after-receiving guard. Full
suite green.

## Auto-generated SKUs, variant attributes now readable by admin (2026-09-24, later same day)

**Phase C of the admin-dashboard rework.** Two small, additive changes, both verified live against a
real database (not just unit-tested):

- `CreateProductRequest.CreateVariantRequest.sku` is now optional (was `@NotBlank`). When a caller
  omits it, `ProductService.generateSku()` builds one from the product's subcategory-or-category slug
  (first 4 uppercase letters) plus a new atomic Postgres sequence (`variant_sku_seq`, migration
  `V23__variant_sku_sequence.sql`, `START WITH 1000`) — e.g. `MAKE-001000`, `LASH-001045`. No
  category → `SKU-NNNNNN`. An explicitly supplied SKU is still honored as-is (data migration,
  matching a supplier's own code). One sequence, not per-category, so generation stays race-free
  under concurrent inserts with zero extra locking.
- `ProductVariantDto` gained an `attributes` field (mirrors `CatalogVariantDto.AttributeValueSummary`
  exactly). `attributeValueIds` on `CreateVariantRequest` was already fully wired into the
  `variant_attribute_value` join table — it just never read back out anywhere the admin UI could see
  it. Threaded through `variantDtos()`, `listProducts()`, `createVariant()` and `updateVariant()`, all
  via the same batch-fetch-then-filter pattern `CatalogVariantDto` already used (no N+1s introduced).

New `VariantSkuAndAttributesIntegrationTest` (4 tests). Full suite still green.

## Staff management: invite/list/re-role/disable staff accounts (2026-09-24)

**Phase B of the admin-dashboard rework** (plan: owner asked for a proper Users tab — previously the
only way to add a staff member was editing `BOOTSTRAP_*` env vars and redeploying). New
`com.nbh.backend.staff.controller.StaffController` at `/api/v1/admin/staff` — `GET` (list), `GET
/roles`, `POST /invite`, `PATCH /{id}/role`, `PATCH /{id}/status` — gated by the `USER_VIEW` /
`USER_CREATE` / `USER_MANAGE_ROLES` permissions that already existed in the `Permission` enum but had
never been wired to an endpoint. Only SUPER_ADMIN/DEVELOPER have `USER_CREATE`/`USER_MANAGE_ROLES` by
default; BRANCH_MANAGER can view the list but not invite or re-role.

**No schema migration, no new token table.** The invite flow reuses `StaffPasswordResetService`'s
existing OTP mechanism end to end — a new `issueInvite(StaffUser)` method there calls the same private
`issueOtp()` used by password resets and publishes a "you're invited" email instead of a "reset your
password" one, through the same `CustomerEmailRequestedEvent`/`CustomerEmailListener` pipeline. An
invite genuinely *is* a first-time password reset (brand-new account, unusable random password,
`mustChangePassword=true`), so the invitee sets their password through the exact same public
forgot-password/reset-password page shoppers and existing staff already use — no separate accept-invite
endpoint or page needed.

**Status is derived, not stored**: `StaffDtos.StaffDto.from()` computes PENDING (never completed a
reset — true for a fresh invite *and* for the legacy env-var-seeded accounts until their first sign-in),
DISABLED (`enabled=false`), or ACTIVE from fields that already existed on `StaffUser`. A SUPER_ADMIN/
DEVELOPER can't disable their own account (`StaffManagementService.setEnabled` checks
`actingStaff.getId()`).

New `StaffManagementIntegrationTest` (7 tests) covers: no-token refusal, a role without `USER_CREATE`
being blocked from inviting but not from listing, invite creating a PENDING member and actually sending
mail (`verify(emailSender, ...)`), duplicate-email conflict, role change + disable + reactivate through
the real API, and the self-disable guard. Verified live end to end in the browser too (invite -> emailed
code appeared in the `LoggingEmailSender` log with the right dynamic name/code/URL -> list updated ->
disable/reactivate toggled) against a second local instance on port 8082, since the port-8081 dev server
already running in this checkout was serving stale/broken responses for the new routes — almost
certainly a devtools hot-reload artifact from a concurrent session's uncommitted Co-op Bank work also
sitting in this checkout (`SecurityConfig.java`, `BankCreditService.java`, `application.yml`,
`payment/coop/**` — untouched, not staged, not part of this commit).

## Branded HTML for the OTP reset emails, real CORS fix note, Brevo confirmed reachable (2026-09-23, later same day)

**Real bugs found and fixed live this round, in production:** the deployed staging frontend had
`.env`'s `VITE_API_URL` still pointing at `http://localhost:8081` (a long-standing, previously-flagged-
but-never-actually-fixed issue — see the frontend HANDOFF) — every request from a real visitor was
failing. Fixed on the frontend side; once that landed, the *next* real failure surfaced was
`CORS_ALLOWED_ORIGINS` on Railway not including the deployed origin — a Railway dashboard fix, not a
code one, done directly by the founder. Both are resolved as of this entry: a live reset request from
the deployed site now reaches this backend, generates a code, and **a real Brevo email arrives** (this
was the open question from the OTP entry below — `BREVO_API_KEY`/`MAIL_FROM_EMAIL` are in fact set on
Railway, confirmed by an actual received email, not just by their presence in the Variables list).

**HTML email added, on top of the existing plain-text body (never replacing it — Brevo gets both,
`textContent` as the fallback):**
- New `EmailTemplates.otpCode(logoUrl, greetingName, introLine, code, expiryMinutes)` —
  table-layout, inline-styles-only HTML (the "bulletproof email HTML" pattern: nothing structural
  relies on a `<style>` block, since Gmail and others strip it). Colors are the storefront's actual
  brand palette, sampled to hex via a `<canvas>` fill (`getComputedStyle` echoed the raw `oklch(...)`
  string back unresolved — canvas forces real color resolution) since email clients don't support
  `oklch()`: `#bd0087` fuchsia, `#ffe2ee` the code's highlight box, `#261c21` text, `#71656d` muted,
  `#fffafc` ground.
- `EmailSender` gained a default 4-arg `send(to, subject, textBody, htmlBody)` overload (falls back to
  the existing 3-arg text-only send, so `LoggingEmailSender` and every other untouched call site — the
  verify-email link — needed zero changes). `BrevoEmailSender` overrides it to add `htmlContent` to the
  API body. `CustomerEmailRequestedEvent` gained an optional `htmlBody` field the same way (new 4-arg
  canonical constructor + a 3-arg compatibility constructor so every existing publish site still
  compiles unchanged); `CustomerEmailListener` picks whichever `send` overload actually applies.
- Wired into **both** reset-password flows only (`CustomerAuthService.forgotPassword`,
  `StaffPasswordResetService.requestReset`) — scope was explicitly "refining the resetting password,"
  not every email type. `StaffPasswordResetService` needed `app.frontend-url` re-added (it was removed
  when the OTP rewrite dropped the emailed link, but the logo URL needs it: `{frontendUrl}/trendysh-
  logo.png`, matching the frontend's new stable-path logo — see the frontend HANDOFF).
- **A real gap the tests caught, not just inferred:** the reworded intro line ("...verification code is
  below.") broke both integration tests' OTP-extraction regex, which depended on the old exact phrase
  "verification code is:" — and separately, `@MockitoBean`-mocked interface default methods are
  themselves mocked (no-op), not delegated to the real default body, so every existing
  `verify(emailSender, atLeastOnce()).send(3-arg-signature)` assertion was verifying an overload that
  was no longer the one actually called once an HTML body existed. Both integration tests updated:
  regex now matches the code by position (`\n(\d{6})\n`) rather than a specific preceding phrase, and
  verification now targets the 4-arg overload. Caught by actually running the suite, not assumed.
- New `EmailTemplatesUnitTest` (well-formed HTML, every dynamic value present, and — since `greetingName`/
  `introLine` are real user-influenced strings (a customer's own name) — HTML-escaping is actually
  exercised, not just assumed safe).
- Full suite: **180 run, 0 failures**, fresh test DB.

## Railway deployment confirmed live; DEVELOPER role + dual admin seeding; JUnit coverage for the OTP flow (2026-09-23, later same day)

**Railway, confirmed via the actual dashboard (not just this file's own earlier notes):** project
"Trendysh-Backend", environment `production`, repo `trendysh-accosmetics-backend-ecommerce`
(GitHub, owner `lezisign-star` per the "repository moved" notice on push — matches the note further
down this file), live at `trendysh-accosmetics-backend-ecommerce-production.up.railway.app`,
Postgres alongside it in the same Railway project. Auto-deploys on push to `main`; the OTP commit
below showed up there as "ACTIVE / Deployment successful" within minutes of being pushed, so Flyway
migrations do run automatically on deploy — no manual migration step needed.

**DEVELOPER role added** (`StaffRoleSeeder`) — same full permission set as SUPER_ADMIN, display
name "Developer" only. Two staff accounts are now seeded by email on boot (`StaffUserSeeder`,
password unset — set via the forgot-password OTP flow, never logged or hardcoded):
- `trendyshaccosmetic@gmail.com` → SUPER_ADMIN. The shop owner's own address, given to us directly
  by them for this purpose (not guessed). Config: `app.bootstrap.additional-admin-emails`
  (`BOOTSTRAP_ADDITIONAL_ADMIN_EMAILS`).
- `mdaucodes@gmail.com` → DEVELOPER (moved off SUPER_ADMIN — see below). Config:
  `app.bootstrap.developer-emails` (`BOOTSTRAP_DEVELOPER_EMAILS`), a new property alongside the
  existing one.

**A real gap, found and fixed before this shipped:** `StaffUserSeeder` only ever creates *missing*
accounts — `mdaucodes@gmail.com` already existed as SUPER_ADMIN in every environment (dev, and
Railway production per the V18 entry below), seeded under the *old* `additional-admin-emails`
default before DEVELOPER existed as a role. Simply repointing config at a new `developer-emails`
list does nothing for an account that's already there. Fixed with a one-time, idempotent move in
`StaffUserSeeder.seed()` (`moveToRoleIfPresent`, not a SQL migration — a Flyway migration runs
*before* Spring's `@PostConstruct` seeders, so a SQL `UPDATE` referencing the not-yet-created
DEVELOPER role row would either fail outright or null the FK; doing it in Java after
`StaffRoleSeeder` has already run, via the same constructor-ordering trick this class already used,
sidesteps that entirely). Verified live against the local dev DB across two restarts: first restart
logs `Moved mdaucodes@gmail.com from SUPER_ADMIN to DEVELOPER`, second restart is a clean no-op.
This will do the same thing on Railway production on its next deploy.

**JUnit coverage added for this and for the OTP flow itself** (the earlier OTP entry below was
live-verified by hand, not by an automated suite — this closes that gap):
- `StaffPasswordResetIntegrationTest` rewritten for the OTP shape (was still asserting the old
  `token=` link format — would have failed at runtime against the new `{email, code, newPassword}`
  API, just never actually run since the code compiles fine either way). Added coverage for the
  5-attempt lockout, the exact "N attempts left" messages, and a locked code staying rejected even
  when the right one is finally tried.
- `CustomerPasswordResetIntegrationTest` — new; the shopper side of this flow had **zero** test
  coverage before (the staff test file only ever exercised the staff branch). Same lockout coverage,
  plus confirms a successful reset also verifies the email and links earlier guest orders, matching
  the old link-based behaviour.
- `StaffUserSeederUnitTest` — new, Mockito-only (no DB/Spring context), for the seeding/move branch
  logic specifically: moves a pre-existing SUPER_ADMIN mdaucodes account, leaves an already-correct
  one untouched, no-ops when the account has never existed, seeds a fresh developer-emails entry.
- **Found and fixed a pre-existing, unrelated broken test** while running the full suite against a
  genuinely fresh `nairobi_beauty_hub_test` (per this file's own standing "fresh DB" note — it did
  its job again): `CustomerAccountsIntegrationTest.aShopperWhoRegistersWithAStaffEmailStillCannotBecomeStaff`
  assumed a shopper could still register on a staff email and fall back to signing in — but
  `CustomerAuthService.register()` has since been hardened to block that outright (409, prevents
  staff-email probing). The test only ever passed because of leftover state in a long-reused test
  DB from before that hardening existed. Rewritten to assert the actual current behavior
  (409, staff login unaffected); renamed to
  `aShopperCannotRegisterOnAStaffEmailAndStaffLoginIsUnaffected`.
- Full suite: **177 run, 0 failures**, fresh test DB, twice in a row.

## Password reset: link → 6-digit OTP, shared by shoppers and staff (2026-09-23)

Both `CustomerAuthService.forgotPassword/resetPassword` and `StaffPasswordResetService`
(still one asks the other first — staff email short-circuits the shopper path, unchanged)
now issue/verify a 6-digit numeric code instead of a link token. Only `VERIFY_EMAIL` is
still a link (unaffected).

- **Why an OTP-specific redesign, not just a shorter token:** a 6-digit code only has
  1,000,000 values, so the old `UNIQUE(token_hash)` DB constraint became a real collision
  risk (birthday paradox) once more than a handful of resets are in flight — dropped in
  V22. Lookups are now scoped to the account/staff row (latest `createdAt`) instead of a
  global hash lookup.
- **Lockout:** new `attempts` column (`int`, not `SMALLINT` — Hibernate's `ddl-auto=validate`
  will refuse to boot on that mismatch, learned the hard way this session). 5 wrong guesses
  locks that code (`usedAt` set immediately, so even the right code stops working — request
  a fresh one). Each wrong guess **must** persist even though the request handler then
  throws — both `resetPassword` and `tryReset` need `@Transactional(noRollbackFor =
  UnprocessableException.class)`, same pattern as `CustomerRefreshTokenService`'s reuse
  detection, or the attempts increment silently rolls back with the exception.
- **TTL:** 10 minutes (was 1 hour for the link). Resend cooldown unchanged (2 min).
- **API shape change:** `POST /api/v1/customer/auth/reset-password` body is now
  `{ email, code, newPassword }`, not `{ token, newPassword }` — the OTP alone can't
  identify an account the way a globally-unique link token could, so the email has to come
  along. `CustomerDtos.ResetPasswordRequest` updated to match; `code` validated
  `@Pattern(\\d{6})`.
- **Frontend:** `/reset-password` route deleted; `forgot-password.tsx` is now a two-step
  page (email → code+new-password) with a "Resend code" button and a "Wrong email? Change
  it" escape hatch back to step 1 — no more token-in-URL. `customerApi.resetPassword(email,
  code, newPassword)` signature changed to match.
- Live-verified end-to-end this session, both paths: staff (admin@nairobibeautyhub.co.ke)
  and a throwaway customer account, including the 5-attempt lockout and a post-lockout
  correct-code rejection, through curl **and** the actual browser UI (resend button, code +
  password entry, redirect to `/login`, sign-in with the new password).

## Merchandising backend — Flash Deals / New Arrivals / Popular / site-wide campaigns (2026-09-22)

Built + tested + pushed to `main`. Full design: `C:\Users\ADMIN\.claude\
NairobiBeautyHub_Merchandising_Promo_Plan.md` — that file's own "Backend build log" section has
the real deviations found while building (checkout doesn't need to call the price resolver, a real
description-wiping bug in the Quick-edit panel found and fixed, exact migration number). Read it
before touching this feature again; not re-duplicated here. One-line status: backend done, **admin
UI and storefront rails are next and not started** — including a required small follow-up on the
existing admin product editor's save calls (see the plan doc). Suite: 164 run, 0 failures, 1
pre-existing unrelated error (same one already documented below).

## Order fulfilment lifecycle — PICKING/READY/DELIVERED are now real (2026-09-22, later same day)

Fixes gap 1 in the "READ THIS FIRST" section directly below — that section's own text is now
stale on this one point; this section supersedes it. The user chose the fuller of the two options
offered ("build real fulfilment stages" over "just simplify the tracker UI").

- `OrderFulfilmentService`: three new methods. `startPicking` (PAID -> PICKING) and `markReady`
  (PAID or PICKING -> READY) do a pure status flip, no stock movement — the one real stock
  movement still happens only in `fulfil` (now accepts PAID/PICKING/READY, not just PAID, so a
  small operation can still skip straight to dispatch same as before). `markDelivered`
  (DISPATCHED -> DELIVERED) is the piece that was missing entirely — a manual staff action
  (confirmed by phone/WhatsApp with the rider/customer; there's still no automated courier
  webhook). `CANCELLABLE_STATUSES` widened to include PICKING/READY too (no stock has moved yet
  at those stages either, same reasoning `fulfil`'s precondition already used).
- Three new events (`OrderPickingStartedEvent`, `OrderReadyEvent`, `OrderDeliveredEvent`),
  log-only via `EventLoggingListener` for now, same v1-seam pattern as every other order event —
  a real consumer (customer SMS/email on each stage) is a listener to add later, not a service
  change.
- Three new endpoints on `OrderController`: `POST /orders/{id}/start-picking`,
  `POST /orders/{id}/mark-ready`, `POST /orders/{id}/mark-delivered` — same
  `PERM_ORDER_MANAGE_ALL` gate and `@Transactional` (ArchUnit's ControllerArchitectureTest checks
  both) as `fulfil`/`cancel`.
- `fulfil`'s precondition exception changed from `IllegalStateException` to `UnprocessableException`
  while touching that line anyway — matches this codebase's own stated preference for the typed
  exception taxonomy over the legacy safety net for new/touched domain-error code.
- 9 new unit tests in `OrderFulfilmentServiceUnitTest` (happy path + invalid-state path for each
  new method, plus two new cancel-from-PICKING/READY cases), same result-driven/ArgumentCaptor
  style as the existing cancel tests. Full suite: 149 run, 0 failures, 1 pre-existing unrelated
  error (`CustomerAccountsIntegrationTest`, a JSON-parsing NPE in a staff-email-registration test —
  confirmed unrelated before this work started, not touched, not fixed here).
- **Live-verified end-to-end**, not just unit-tested: placed two real guest orders via the actual
  API (one BRANCH_PICKUP, one DELIVERY_COURIER/BODA) against a freshly-started dev-DB backend,
  confirmed payment, then walked PAID -> PICKING -> READY -> DISPATCHED -> DELIVERED — checking
  `GET /orders/track/{orderNo}` after every step. Also clicked through the real admin UI (see the
  frontend HANDOFF's matching entry) and the real customer tracker page in the browser: all 6
  steps light up correctly in order, ending at "Delivered" instead of getting stuck at step 5
  forever as gap 1 described.
- **Not done / correctly deferred**: no notification listener on the three new events yet (staff
  and customers still only find out via the admin UI / a phone call, same as before); PICKING and
  READY still have no timestamp columns (only the single `status` enum field moved, matching how
  every other status transition in this entity already works — a `pickingStartedAt`/`readyAt`
  pair would be a reasonable later addition if the founder wants stage-duration reporting, not
  needed for the tracker itself).

## READ THIS FIRST (2026-09-22) — corrects stale status further down, and scopes the next feature

**Deploy is DONE, not a blocker anymore.** Despite what older sections below still say: the backend
is live on Railway (`trendysh-accosmetics-backend-ecommerce-production.up.railway.app`, Dockerfile
build, `DATABASE_URL` auto-converted, repo now at `lezisign-star/trendysh-accosmetics-backend-
ecommerce`), and the frontend's `staging` build points at it via a committed `.env.production`. The
six launch categories are seeded (V16). Don't re-do this work or treat it as open.

**Also shipped since the "START HERE" sections below were written** (all pushed to `main`, tests
green each time): CORS/CRLF/origin fixes for the deploy; shopper sessions hardened (15-min access
token + rotating httpOnly refresh cookie, V17); a single `/login` page for staff and shoppers
(staff checked first, forced password change on first login, `/admin/login` now redirects); staff
password reset via the same forgot-password flow, plus an `mdaucodes@gmail.com` SUPER_ADMIN seeded
on boot (V18); a full product-editor API — category/subcategory hierarchy with consistency rules,
admin search/filters, a "set stock quantity" endpoint that posts through the real stock ledger
(`VariantStockService`), soft delete/restore, category rename/delete with product-count guards.
Frontend got a matching admin product/category UI (Quick + Full edit panels, list/grid, mobile-
first) and real customer-facing category → subcategory browsing (shop sidebar tree, breadcrumbs,
subcategory-first related products) — see the frontend HANDOFF for that side.

**Two real gaps found live 2026-09-22 — #1 fixed later the same day, see the section above this one:**
1. ~~`OrderFulfilmentService.fulfil` jumps a `PAID` order straight to `DISPATCHED`...~~ **FIXED** —
   see "Order fulfilment lifecycle — PICKING/READY/DELIVERED are now real" at the top of this file.
2. No inventory/low-stock dashboard in the admin UI. The backend already has one (`GET
   /stock/inventory`, `belowReorderOnly` filter) and the Overview page shows a "Low stock items"
   count tile, but nothing lets staff click it through to a list — stock is only visible/editable
   one product at a time via the product editor.

**Next scoped feature (not started): merchandising/promos.** Full spec, schema, endpoints and build
order written up after founder decisions were collected: `C:\Users\ADMIN\.claude\
NairobiBeautyHub_Merchandising_Promo_Plan.md` — Flash Deals (per-product deal price + countdown),
New Arrivals and Popular rails (manual flags, Popular falls back to real sales ranking), and a
site-wide percent-off campaign shown as one compact banner. Read that file before starting it; this
paragraph is a pointer, not the spec.

**Ongoing elsewhere in this file below:** another session is mid-way through Co-op Bank payment
work (INS receiver, claims, bank credits) — uncommitted as of 2026-09-21/22. Don't assume it's done
or tested against a real bank; its own sections say so explicitly. Leave those files/sections alone
unless you're continuing that specific work.

---

## CO-OP INS RECEIVER (2026-09-21, committed 2026-09-24) — built from the bank's PUBLIC spec, NEVER tested against the real bank
`POST /api/v1/payments/coop/ins` (`com.nbh.backend.payment.coop.*`): Basic-auth check (`COOP_INS_USERNAME/PASSWORD`,
blank = reject everything), records CREDIT events for `COOP_INS_ACCOUNT_NUMBER` via `BankCreditService.record(...,
IPN, autoMatch)`. **`COOP_INS_AUTO_MATCH` defaults to false**: credits are recorded for staff to allocate on
`/admin/payments`; turn on only after verifying against the real bank (INS has only a shared Basic credential, no
signature). Reference is searched in Narration + CustMemoLine1-3 (bank has not said which). Ack body shape is an
assumption. NOT built: AccountTransactions reconciliation (needs OAuth creds + sandbox), SafaricomSTKPush (see
Coop-STK-Push-Configuration-Guide.pdf in Downloads: ~30% chance of production access in 6 weeks; do not depend on it).
Suite on a fresh DB, re-run at commit time: 200/200 (the earlier 139/140 note's stale failure no longer reproduces).


> **IMPORTANT: the Co-op Bank (or Family Bank) integration is NOT DONE.** It is untested and no real credentials exist. Everything below is bank-agnostic scaffolding confirmed manually by staff. There is no bank IPN endpoint, no reconciliation job and no bank API client. Do not describe bank payments as automatically verified until the real bank integration is built and tested against the bank's sandbox.

## BANK PAYMENTS — step 3 built (2026-09-21, UNCOMMITTED, suite 122/122)
Customer "I've paid" claims + staff queues + bank credits (V20: `payment_claim`, `bank_credit`,
append-only, nothing deleted). Public `POST /payments/pay/{orderNo}/claim?ref=` (max 3 pending per
payment, duplicate code = 409) records a claim, extends stock hold + window 12h, NEVER confirms.
Staff `/api/v1/admin/payments/*` (PERM_PAYMENT_MANAGE or ORDER_MANAGE_ALL): awaiting, claims
(approve => `confirmSuccess`, reject needs reason), credits (record, allocate to order, ignore
with reason). `BankCreditService.record` is THE entry point the bank IPN + reconciliation job
must call (idempotent on bank_txn_id; auto-match only on valid reference AND exact amount; a
credit for an already-paid order is left UNMATCHED, never silently matched).
NEXT: bank IPN endpoint (needs bank creds), reconciliation job, underpay/overpay/late-pay
(PAYMENT_REVIEW), SMS, refunds, screenshot upload on claims.


## BANK PAYMENT COLLECTION — step 1 built (2026-09-21, UNCOMMITTED, suite 109/109)

Client wants payment by BANK (Co-op/Family), not Daraja. Design = per-order
reference + bank credit notification + reconciliation (see the design in the
2026-09-21 session). **Step 1 (this) is shippable with zero bank integration:**
checkout creates a `BANK` PaymentRecord with a `payment_reference` (V19) and a
payment window; the customer's `/pay/{orderNo}?ref=` page shows paybill/bank
details; staff confirm with the existing `confirm-manual` until the bank IPN
exists. Built: `PaymentReferenceGenerator` (7 chars, vowel-free, check digit),
`BankPaymentSettings` (`app.payments.*`, `app.bank.*` env vars),
`PaymentInstructionsService` + public `GET /api/v1/payments/pay/{orderNo}?ref=`,
`CheckoutResponseDto.paymentInstructions`. `PAYMENT_COLLECTION_MODE=MPESA`
restores the old Daraja path (15-min hold). Window default 45 min
(`PAYMENT_WINDOW_MINUTES`); stock hold and payment window are the same instant.
Set `BANK_NAME`, `BANK_PAYBILL_NUMBER`, `BANK_PAYBILL_ACCOUNT_TEMPLATE`
(uses `<ref>`, NOT `#{ref}`: Spring parses `#{...}` as SpEL and fails startup —
found live), `BANK_ACCOUNT_NAME/NUMBER/BRANCH`, `SUPPORT_WHATSAPP` on Railway.
Blank paybill hides that tab; blank account hides the transfer tab.
**Not built yet (in order):** status polling is done client-side already;
NEXT = proof-of-payment claims + admin queues (`/admin/payments`), bank IPN
endpoint (needs bank credentials), reconciliation job, underpay/overpay/late-pay
handling (`PAYMENT_REVIEW` status), SMS, refunds. Everything must stay
append-only and eTIMS-compatible (every payment recorded, nothing deleted).
Local test DB: `DB_URL=jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test
DB_PASSWORD=janet-marketing` (a dev DB `nairobi_beauty_hub` does not exist).


**Active multi-session plan:** `C:\Users\ADMIN\.claude\NairobiBeautyHub_Integration_Readiness_Plan.md`
— a phased plan (backend gap-closing, then frontend auth/wiring/checkout/
inventory-redesign) to connect the frontend and backend for real, written
after a completeness tour of both repos found the real gaps (no staff order
list endpoint, no analytics endpoints, frontend admin auth is fake, frontend
product model doesn't match the backend's variant schema, etc.). **Read this
before starting any "wire frontend to backend" work** — it has the correct
dependency order and explains why, e.g., admin auth must be wired before any
other admin screen can work at all. **Phase 1 (backend gap-closing) and
Phase 2 (frontend real auth) are both done** — see "Session 11" below.
Phase 3 is next. The plan doc also now has a **Backlog section (items 7-9)**
added 2026-09-18 at the founder's explicit request — customer accounts/order
tracking, site-wide visit/traffic analytics, and admin-managed promo
banners with linked products. None of those are started; they're
deliberately sequenced AFTER Phases 1-6, not next.

**Status as of 2026-09-18 (session 11):** Phase 1 of the integration
readiness plan complete — staff order list/search/cancel, a staff inventory
browse endpoint, and admin analytics (revenue/order-counts/low-stock/top-
sellers) all built, unit-tested, and live-verified end to end against
`nairobi_beauty_hub_dev`. Full suite: 50 tests, all green. See "Session 11"
below for the two more null-parameter/PostgreSQL type-inference bugs found
live (same bug class as session 10, different shapes) and how each was fixed.

**Status as of 2026-09-18 (session 10):** Phases 0-2 unchanged; session 10
added the customer-facing catalogue read side (search/browse/related
products/categories), Cloudinary signed image upload, verified-purchase
reviews, and sales/view analytics events on top of the Phase-0 catalogue
schema that already existed. See "Session 10" below for full detail,
including two real bugs found live (a silently-dropping implicit INNER JOIN,
and a null-parameter type-inference crash) that would not have been caught by
compiling or unit tests alone.

**Status as of 2026-09-17 (session 9):** Phases 0-2 built and live-verified.
The session-7 improvement plan's **Tier 1 (all 8 items) and most of Tier 2
are now done** — C5/C6/C7/C8/C9 were completed in an untitled session between
8 and 9 (not documented here at the time; verified against actual source in
session 9 before trusting this file, per this project's own standing rule),
and session 9 closed the Boot version bump (3.3.4→3.5.16), actuator/metrics,
and a real regression found live during that work (see "Session 9" below).
**Remaining Tier 2:** C10 (auth entry point), C12 (single availability
definition), C14 (BigDecimal-typed stock request fields). **All of Tier 3**
remains open (virtual threads, OAuth2 Resource Server, transactional
outbox, `@Scheduled` multi-instance guard, Testcontainers).

Full design rationale: `C:\Users\ADMIN\.claude\NairobiBeautyHub_Platform_Architecture.md`
(read this first — it explains *why* every table/service below exists, with
Odoo/ERPNext source citations). The full audit is
`C:\Users\ADMIN\.claude\NairobiBeautyHub_Backend_Improvement_Plan.md` — still
worth reading for the remaining Tier 2/3 items and the reasoning behind every
fix. **This backend is one half of the "Trendysh Accosmetic" project** — see
the frontend's own `HANDOFF.md` at `J:\frontend\nairobi-beauty-hub-main` for
the 2026-09-15 rebrand (business name, categories, colors, and a business-
model change to delivery/fees that session 9 aligned this backend to).

## Founder decisions confirmed 2026-09-12 (closes architecture doc §8 Q1-Q5)

1. **Payment provider: M-Pesa via Daraja direct** (not PayHero) — same
   integration pattern as Moments Packaging's `DarajaService`.
2. **One shared catalogue, no branch-exclusive products.** Founder's own
   words: e-commerce doesn't distinguish branches at all; dispatch can come
   from either branch. The design doc's default (`branch_product_availability`
   table NOT needed) stands as originally proposed.
3. **Cart reservation: none at add-to-cart; reserve at checkout initiation
   with a 15-minute TTL.** Matches `StockReservation.expiresAt` already
   modelled with `ONLINE_CART` as a distinct source from `ONLINE_ORDER`.
4. **Order fulfilment branch: a fixed default branch for now** (Branch A is
   already seeded `is_default_online_fulfilment = true` in `V4`). Smarter
   assignment (nearest-to-address, whichever-has-stock) is explicitly
   deferred, not decided against — revisit once there's real order volume.
5. **Negative stock: hard-blocked, confirmed** — matches what's already
   implemented (`StockPostingService.applyMovement`'s guard).

These unblock Phase 2 (cart/checkout/orders) once Phase 1 hardening (below)
is complete.

## What's built

- Maven/Spring Boot 3.3.4, Java 21, Postgres + Flyway — same shape as Moments
  Packaging's backend (`pom.xml`, `mvnw` wrapper copied from there).
- **Flyway migrations** (`src/main/resources/db/migration/`):
  `V1` branches + stock_location (2 branches seeded, physical + virtual
  locations), `V2` catalogue (product/variant/attributes/images/barcodes),
  `V3` the stock core (stock_document, stock_document_line,
  stock_ledger_entry — DB-trigger-enforced append-only — stock_balance,
  stock_reservation), `V4` seeds Branch A / Branch B and their locations.
- **Entities + repositories** for all of the above (`branch/`, `catalog/`,
  `stock/` packages).
- **`StockPostingService`** (`stock/service/`) — the single enforced write
  path for every stock quantity change. Implements the sorted-lock,
  balance-row-first locking protocol from the design doc §4.5/§4.3.
- **`StockReservationService`** — the anti-oversell mechanism unifying
  online-cart/order and POS-ticket holds against one `stock_reservation`
  table.
- **`StockBalanceRebuildService`** — the from-ledger consistency check.
- **`SingleWritePathArchitectureTest`** (ArchUnit) — mechanically fails the
  build if any class outside `stock.service` ever writes
  `stock_ledger_entry`/`stock_balance` directly. **Passes.**
- **`ConcurrentReservationRaceTest`** — 12 threads race for the last unit of
  one SKU across simulated ONLINE_ORDER/POS_TICKET reservations; asserts
  exactly one wins. **Written but not yet run** — needs a real local Postgres
  test database; see below.

Verified: `./mvnw compile` and `./mvnw test-compile` both succeed with no
errors (41 main classes). The ArchUnit test has been run and passes.

## Session 2 additions — staff auth/RBAC

- **`V5__staff_auth_and_rbac.sql`** — `staff_role` (+ `staff_role_permissions`
  collection table), `staff_user`, `staff_branch_assignment` (the
  branch-scope dimension Moments' RBAC lacks — design doc §4.8).
- **`Permission`** enum, **`StaffRole`**, **`StaffUser`** (implements
  `UserDetails`, staff-only — no customer auth yet, that's Phase 2),
  **`StaffBranchAssignment`**.
- **`StaffRoleSeeder`** — seeds 5 default roles on boot (SUPER_ADMIN,
  BRANCH_MANAGER, CASHIER, STOCK_CONTROLLER, VIEWER) and re-syncs any
  role still flagged `isDefault` whenever `Permission` gains new values,
  mirroring Moments' `StaffRoleSeeder` pattern.
- **`StaffUserSeeder`** — creates one bootstrap SUPER_ADMIN account on first
  boot (email from `BOOTSTRAP_ADMIN_EMAIL`, default
  `admin@nairobibeautyhub.co.ke`), logs a random temp password **once**,
  flags `mustChangePassword`. Depends on `StaffRoleSeeder` to force boot
  ordering (`@PostConstruct` order isn't guaranteed across unrelated beans).
- **`JwtService`/`JwtAuthFilter`/`SecurityConfig`** — adapted from Moments,
  trimmed to staff-only (no cookie auth, no impersonation, no refresh token
  yet — access tokens are 8h-lived via `app.jwt.access-token-expiration-ms`
  until a refresh flow is built).
- **`AuthController`** — `POST /api/v1/auth/login`,
  `POST /api/v1/auth/change-password`, `GET /api/v1/auth/me`.

**Set a real `JWT_SECRET` env var before any non-local deployment** — the
`application.yml` default is a placeholder that says so in its own comment.

## To run against a real database

```bash
createdb -h localhost -U postgres nairobi_beauty_hub_test
./mvnw test -Dtest=ConcurrentReservationRaceTest
```

Set `DB_USERNAME`/`DB_PASSWORD` env vars if your local Postgres isn't the
default `postgres`/`postgres` — this session didn't have the local Postgres
password, so neither the concurrency test nor a full app boot (Flyway
migrate + all seeders) has been run yet. The `ConcurrentReservationRaceTest`
is the Phase-1 gate the architecture doc calls for — don't build anything on
top of the stock core until it's green on your machine. Also worth doing:
`./mvnw spring-boot:run` against the same test/dev DB and confirm the
bootstrap SUPER_ADMIN log line appears and `POST /api/v1/auth/login` works.

## Session 3 additions — REST layer + live end-to-end verification

- **Controllers**: `BranchController` (list/get/create branch, list a
  branch's locations), `ProductController` + `ProductService` (create a
  product with N variants/attributes/barcodes in one call — the "easy to
  add a product" flow — plus list/get), `CategoryController`,
  `BrandController`, `StockController` (`/stock/receive`, `/stock/adjust`,
  `/stock/balance`), `StockReservationController`
  (`/stock/reservations` reserve/release/fulfill).
- **`GlobalExceptionHandler`** — maps `EntityNotFoundException` (404),
  `InsufficientStockException`/`InsufficientAvailabilityException` (409),
  validation failures (400), bad credentials (401) to sane JSON error bodies.
- **Given a real Postgres password this session**, ran the full loop against
  a live local Postgres: `dropdb`/`createdb`, full `mvnw test` (both tests
  green), then `mvnw spring-boot:run` and drove the actual HTTP API with
  curl — login, create product+variant, receive 50 units, reserve 5,
  adjust -3, confirmed balances at every step (`SUPPLIER` went to -50 as
  designed, `BR-A/SALES_FLOOR` correctly showed on_hand 47 / reserved 5 /
  available 42 with avg_cost 900.00 after the moving-average update).

**Four real bugs found and fixed during that verification pass** (not
hypothetical — each one failed a real test or a real curl call first):
1. Seven tables were missing `created_at`/`updated_at` columns their JPA
   entities require (`product_attribute`, `product_attribute_value`,
   `variant_barcode`, `product_image`, `stock_document_line`,
   `stock_balance`, `stock_reservation`) — Hibernate schema validation
   failed on boot. Fixed in `V2`/`V3` directly (nothing was deployed yet).
2. **The negative-stock guard was blocking virtual locations.** Receiving
   goods failed because `SUPPLIER` (correctly meant to go negative — it's
   the "infinite" other side of every receipt) tripped the same
   hard-block as physical locations. Fixed: the guard in
   `StockPostingService.applyMovement` now checks `location.isInternal()`;
   also removed the blanket `CHECK(on_hand_qty >= 0)` from the
   `stock_balance` migration for the same reason.
3. **Ledger entries were being saved before their parent document/lines
   were persisted** — `StockLedgerEntry.document`/`documentLine` are
   NOT NULL FKs, and Hibernate can't resolve a reference to a still-
   transient entity. Fixed: `post()` now `saveAndFlush`s the document
   (cascading its lines) before building any ledger entries.
4. **`StockController.balance()` threw `LazyInitializationException`** —
   `StockBalance.variant`/`.location` are lazy, and the controller method
   wasn't transactional, so the Hibernate session was gone by the time the
   DTO mapper accessed `variant.getSku()`/`location.getCode()`. Fixed with
   `@Transactional(readOnly = true)` on the endpoint.

## Session 4 — Opus audit fixes (F1-F7 of 12 findings)

An independent Opus audit (`C:\Users\ADMIN\.claude\NairobiBeautyHub_Backend_Audit_Session3.md`)
confirmed session 3's four bug fixes were correct, then found 12 new issues —
two critical. All of F1-F7 are now fixed and covered by passing tests
(7 tests across 4 classes, all green against real Postgres):

- **F1 (critical)** — `StockReservationController`'s `reserve`/`release`/
  `fulfill` were gated on `PERM_STOCK_VIEW`, a read-only permission `VIEWER`
  holds. Fixed: now `hasAnyAuthority('PERM_POS_SELL','PERM_ORDER_MANAGE_ALL','PERM_STOCK_ADJUST')`.
- **F3 (critical)** — the negative-stock guard only checked `on_hand >= 0`,
  never `on_hand >= reserved_qty`, so a write-off or unreserved sale could
  silently consume a paying customer's already-reserved unit — the exact
  oversell this whole subsystem exists to prevent. Fixed: `StockDocumentLine`
  now carries an optional `reservation` link; `StockPostingService` enforces
  a reserved-qty floor on unreserved outbound movements and, when a line
  does reference its own reservation, closes that hold (decrements
  `reserved_qty`, marks `FULFILLED`) in the SAME transaction as the ledger
  append. New migration `V6__stock_document_line_reservation_link.sql`.
- **F2 (critical)** — the ArchUnit single-write-path rule matched only
  `save(Object)` by exact overload (missing `saveAndFlush`, `saveAll`,
  `delete*`, dirty-checking entirely) and was scoped by package, not by
  class. Fixed: `SingleWritePathArchitectureTest` now has 3 rules matching
  call targets structurally (owner + method-name prefix) and naming
  `StockPostingService`/`StockReservationService` explicitly. Verified: the
  audit empirically proved the old rule's gaps with a throwaway violating
  class before I rewrote it.
- **F4 (high)** — `StockReservationService.release`/`fulfill` read the
  reservation with an unlocked `findById`, so two concurrent terminations of
  the same hold could double-decrement `reserved_qty`, silently hidden by a
  `.max(ZERO)` clamp. Fixed: both now lock the balance row first, then
  lock the reservation itself (`StockReservationRepository.lockById`) and
  re-check `ACTIVE` status inside that lock; the clamp is gone so a real
  accounting error now hits the DB's `CHECK(reserved_qty >= 0)` instead of
  being absorbed.
- **F5 (high)** — the "first balance creation" and idempotency-key retries
  were written to catch-and-retry inside the SAME `@Transactional` method,
  which cannot work on Postgres: once any statement in a transaction fails,
  the whole transaction is aborted and every subsequent statement — including
  the "retry" — fails too ("current transaction is aborted"). **Caught by my
  own new test** (`ConcurrentPostingRaceTest`) before this was written up as
  fixed. Real fix: `StockBalanceRepository.insertZeroIfAbsent` is a native
  `INSERT ... ON CONFLICT (variant_id, location_id) DO NOTHING`, which never
  raises, so the locking SELECT after it always succeeds. The document-level
  idempotency race is left as an honest gap (no caller sets `idempotencyKey`
  yet) — a real collision now surfaces as 409 via `GlobalExceptionHandler`,
  not a raw 500.
- **F6 (medium)** — `/stock/receive` and `/stock/adjust` accepted virtual
  locations (`SUPPLIER`/`CUSTOMER`/`ADJUSTMENT`/`IN_TRANSIT`) as the target,
  bypassing the negative-stock guard entirely. Fixed: both now reject any
  non-internal or inactive location via `findInternalActiveLocationOrThrow`.
- **F7 (medium)** — `StockBalanceRebuildService` was dead code (nothing
  called it) and, worse, didn't actually rebuild anything — it compared the
  cached balance against `findLatestQtyAfter`, which reads the SAME
  denormalised value the write path wrote, so it could never disagree with
  itself. Fixed: real single-pass reconciliation via
  `SUM(qty_change) GROUP BY (variant, location)`, plus the double-entry
  invariant check (`SUM(qty_change)` per variant across ALL locations = 0).
  Exposed behind `GET /api/v1/admin/stock/consistency-check`
  (`PERM_SETTINGS_MANAGE`).

**New tests closing the Phase-1 gate** (the architecture doc's three-clause
gate was previously only 1/3 covered — see the audit report §3):
`ConcurrentPostingRaceTest` (exactly one posting wins the last unit,
`on_hand` never negative; all concurrent first-receipts for a never-before-
stocked pair succeed with no lost updates) and `RebuildFromLedgerTest`
(a mixed receipt/sale/transfer sequence reconciles exactly). All 3 gate
clauses are now real, passing tests.

## Session 5 — F8-F12 closed, plus founder decisions confirmed

All remaining audit findings from session 3's independent Opus audit are now
fixed (F1-F12, all 12). Full suite still green (7 tests, 4 classes) after
each change, plus a live boot + HTTP smoke test for the two hardest to
unit-test (JWT startup guard, sequential document numbering).

- **F8 (medium)** — the dev JWT secret had no guard. Fixed:
  `JwtService.validateSecretIsNotTheDevDefaultOutsideDev` (`@PostConstruct`)
  refuses to boot if the secret still equals the committed dev placeholder
  while no dev/test/default profile is active. Verified live: boots fine
  locally (no profile set = treated as safe), would refuse to start on a
  real deployment profile with `JWT_SECRET` unset. The datasource default
  (`postgres`/`postgres`) was deliberately NOT given the same treatment — a
  bad `DB_URL` already fails loudly by definition (can't connect = won't
  boot), unlike a bad JWT secret which succeeds silently.
- **F9 (medium)** — no sweeper ever expired a lapsed cart hold or gave its
  qty back to `stock_balance.reserved_qty`, so the cached "available"
  figure (used by, e.g., a future storefront stock badge) could diverge
  from `StockReservationService.reserve()`'s own live, expiry-aware
  arithmetic. Fixed: `StockReservationExpirySweeper` (`@Scheduled`, every
  60s by default, `app.stock.reservation-sweep-interval-ms`) flips lapsed
  `ACTIVE` reservations to a new `EXPIRED` status through the same locked
  `terminate()` path release/fulfill use — same double-decrement protection
  (F4) applies. `@EnableScheduling` added to the main application class.
- **F10 (low/medium)** — `document_no` used a random 4-digit suffix (9000
  values/prefix/day); the audit's birthday-bound math showed ~55% collision
  odds at 120 documents/day, >99% at 300. Fixed: `stock_document_counter`
  table (`V7`) backs a real atomic per-(type, day) counter via
  `INSERT ... ON CONFLICT DO UPDATE` — the increment and the read-back
  happen in one `@Transactional` method so the row lock is held across
  both, making it impossible for two callers to receive the same number.
  New format: `GRN-20260912-000001`. **Verified live** — three consecutive
  receipts produced `...000001`, `...000002`, `...000003`.
- **F11 (low)** — moving-average cost was computed at 4dp but
  `stock_balance.avg_cost`/`stock_ledger_entry.avg_cost_after`/
  `stock_value*` were stored at 2dp, so a from-ledger rebuild could not
  reproduce values byte-identically (the doc's own §4.4 acceptance
  criterion). Fixed: widened those columns to `NUMERIC(14,4)` (`V8`) to
  match the computation rather than rounding the computation down and
  losing real precision. `unit_cost` columns deliberately left at 2dp —
  they're direct human/sale-price inputs, not derived averages.
- **F12 (low, partial)** — `GET /api/v1/products` is now paginated
  (`Pageable`, default page size 20) with variants for the whole page
  batch-fetched in one query instead of one-per-product; numeric request
  fields that fail to parse now map to 400 via a new `NumberFormatException`
  handler instead of a raw 500; `BranchController.create` now validates
  (`@Valid`, `@NotBlank`). **The bigger item — `staff_branch_assignment`
  enforcement — is done too**: `StaffBranchAccessService` checks a staff
  account's assigned branch(es) (if any — an account with zero assignments,
  e.g. the bootstrap SUPER_ADMIN, is treated as unrestricted) against the
  target branch on `/stock/receive`, `/stock/adjust`, and
  `/stock/reservations` (reserve); a mismatch is a 403 via a new
  `AccessDeniedException` handler. `release`/`fulfill` by raw reservation
  ID still have no ownership check — left as a known gap until
  carts/tickets exist to check ownership against (same reasoning as F1's
  original fix note).

(See "Founder decisions confirmed 2026-09-12" near the top of this file for
the §8 Q1-Q5 answers — payment provider, catalogue sharing, cart TTL,
fulfilment branch, negative stock. All confirmed; every audit finding from
the session 3 review is now closed. Phase 2 (cart/checkout/orders) is the
genuinely next piece of work.)

## Session 6 — Phase 2: cart, checkout, orders, payments

Guest-only (no customer account system yet — deferred per architecture doc
§2.1). New migrations `V9` (cart/cart_item/customer_order/
customer_order_item/payment_record) and `V10` (order_counter, same
atomic-counter pattern as `V7`'s stock document counter).

**The full loop is built and live-verified end-to-end against real
Postgres** — not just unit tests. Sequence tested: create product → receive
10 units → guest adds 3 to cart (`CartController`, scoped by an
`X-Cart-Session` header, no login) → checkout (`CheckoutController` →
`CheckoutService`) creates a `PENDING_PAYMENT` order **and reserves stock in
the same transaction** (`on_hand=10, reserved=3, available=7` — confirmed via
`/stock/balance`) → manual payment confirmation (`PaymentController`'s
`confirm-manual`, standing in for the real Daraja callback — see below) →
`PaymentService.confirmSuccess` flips the reservation from the 15-minute
`ONLINE_CART` hold to a permanent `ONLINE_ORDER` hold
(`StockReservationService.confirmForOrder`) and the order to `PAID` → staff
calls `POST /orders/{id}/fulfil` (`OrderFulfilmentService`), which posts a
real `SALE_ONLINE` stock document with each line naming the reservation it
closes — **confirmed live**: `on_hand` dropped 10→7, `reserved` dropped
3→0, `CUSTOMER` (virtual) correctly showed +3, order status → `DISPATCHED`.

**Two real bugs found and fixed during that live pass:**
1. `cart_item` extends `BaseEntity` (needs `created_at`) but `V9` only gave
   it `added_at`/`updated_at` — the exact same class of migration/entity
   mismatch as session 3's F-something findings. Fixed directly in `V9`
   (nothing deployed yet).
2. `PaymentRecord.rawResponse`/`rawRequest` are mapped as `jsonb`
   (`@JdbcTypeCode(SqlTypes.JSON)`), but `PaymentController` was passing a
   bare `Map.toString()` (Daraja callback path — `{Body={stkCallback=...}}`
   uses `=`, not valid JSON) and a plain concatenated string (manual-confirm
   path) — both failed Postgres's jsonb parse with `invalid input syntax for
   type json`, surfaced correctly as a 409 via the F5 `DataIntegrityViolationException`
   handler rather than a raw 500, but still wrong. Fixed: both call sites now
   go through a shared `toJson()` helper using Jackson's `ObjectMapper`.

Also proactively avoided a third bug of the exact class session 3's audit
found (F4 in that audit — `LazyInitializationException` from DTO-mapping
outside a transaction): `OrderController.fulfil()` needed `@Transactional`
on the *controller* method itself (not just the service method it calls) so
`OrderDto.from()`'s lazy `variant`/`branch` access joins the same Hibernate
session the service used, instead of hitting a closed one after the
service's own `@Transactional` boundary commits and returns.

**What's real vs. what's a stand-in:**
- `CartService`, `CheckoutService`, `StockReservationService.confirmForOrder`,
  `PaymentService`, `OrderFulfilmentService` are all real, tested, working
  code — not stubs.
- `DarajaPaymentGateway` (M-Pesa STK push) is structurally complete —
  OAuth token fetch, STK push request shape, all following Safaricom's
  documented Daraja v1 API — but **NOT LIVE-TESTED**. `app.mpesa.*` are
  unset placeholders; calling it will fail clearly (refuses to even try
  with blank credentials) rather than silently. Needs real Safaricom
  sandbox credentials (consumer key/secret, shortcode, passkey) before it
  can be trusted. Until then, `PaymentController`'s `confirm-manual`/
  `fail-manual` (staff-only, cash/bank/manually-confirmed M-Pesa till
  payments) are the real path to move an order out of `PENDING_PAYMENT` —
  and stay useful permanently for payment methods that don't have a
  webhook at all.
- Delivery fee calculation is not built — `order.deliveryFee` is always 0.
  `DELIVERY_COURIER`/`DELIVERY_TUMABODA` are accepted as valid
  `fulfilmentType` values but nothing computes a fee or dispatches a rider
  yet.
- Order status only moves `PENDING_PAYMENT → PAID → DISPATCHED` — the
  `PICKING`/`READY` sub-stages exist in the enum but nothing sets them
  yet (deliberately collapsed for this pass — see `OrderFulfilmentService`'s
  javadoc).
- `unitCostAtSale` is captured correctly at fulfilment time from the stock
  ledger's average cost — real margin data, not a placeholder.

## Session 8 — Tier 1 of the session-7 improvement plan (all 8 items)

Worked through `NairobiBeautyHub_Backend_Improvement_Plan.md`'s §5 Tier 1 in
the order given. Every code-behaviour claim in the plan and in this file was
checked against the actual source before relying on it (line numbers had
drifted slightly from Phase 2 work; the substance was accurate). Full test
suite: **45 tests, all green** against a fresh `nairobi_beauty_hub_test`
(11 migrations, `V11` new this session), plus live HTTP verification via
curl against `nairobi_beauty_hub_dev` for every fix with an HTTP-visible
behaviour, the same way every prior session did.

**§4 C1 — the M-Pesa callback was unauthenticated.** `PaymentController.mpesaCallback`
now requires a high-entropy secret path segment
(`/api/v1/payments/mpesa/callback/{secret}`, compared with `MessageDigest.isEqual`
against `app.mpesa.callback-secret`, blank by default = reject everything),
cross-checks the callback's own claimed `Amount` against the `PaymentRecord`'s
before ever confirming success, and binds to a typed `MpesaCallbackRequest`
record instead of `Map<String,Object>` + unchecked casts (a malformed/unknown
body now logs and returns Safaricom's expected 200/Accepted, never a 500).
`SecurityConfig`'s permitAll pattern updated to `.../callback/**`.
**Live-verified** (`nairobi_beauty_hub_dev`, real checkout → payment record,
provider reference set to simulate a completed `initiate-mpesa`): missing
secret → 403 (falls out of permitAll entirely), wrong secret → 404, correct
secret + the attacker's own known `CheckoutRequestID` but no secret → 404
(the exact self-confirm attack the finding described no longer works),
malformed/empty/unparseable bodies → 200 with no crash, unknown reference →
200 with no crash, amount mismatch → order correctly marked `PAYMENT_FAILED`
not `PAID`, matching amount → order correctly flips to `PAID`.

**§4 C2 — `PaymentRecord` status was an unlocked read-modify-write.**
`PaymentRecordRepository.lockById` (`PESSIMISTIC_WRITE`, same pattern as F4's
fix on `StockReservation`) now backs both `confirmSuccess` and `markFailed`.
**Proven, not just asserted**, by a new integration test,
`ConcurrentPaymentConfirmationRaceTest`: two threads call `confirmSuccess`/
`markFailed` on the same payment simultaneously; asserts exactly one applies
and the other is rejected by the guard, and that order status and reservation
state never disagree. Temporarily reverted the fix (back to `findById`) to
confirm the test actually fails without it — **failed 5/5 runs** ("Exactly
one of confirmSuccess/markFailed must actually apply its write" — both
applied), then restored the fix and confirmed green again. This is the same
empirical-proof standard session 4 used for the ArchUnit rewrite.

**§4 C3 — audit finding F1 recurred verbatim.** `confirmManual`/`failManual`
were gated on `PERM_PAYMENT_VIEW` (a read permission) — added `PAYMENT_MANAGE`
to the `Permission` enum, changed both to `hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')`.
No migration needed (`staff_role_permissions.permission` has no CHECK
constraint). **Live-verified**: a seeded VIEWER-role account (only
`PERM_PAYMENT_VIEW`) → 403 on `confirm-manual`; SUPER_ADMIN (now has
`PAYMENT_MANAGE`) → 200 and the payment/order actually confirm.

**§4 C4 — the callback loaded the entire `payment_record` table.**
`findAll().stream().filter(...)` replaced with
`PaymentRecordRepository.findByProviderReference`, backed by a new unique
index (`V11__payment_record_provider_reference_index.sql` — Postgres treats
NULLs as distinct, so CASH/BANK payments without a reference are unaffected).
Exercised implicitly by every C1 callback test above.

**§3 — the Mockito unit-test layer.** All four named targets, all sketched
cases from the plan's §3.4 tables plus the two events tests §3.2a explicitly
calls out as highest-value (the *negative* edge-trigger case). Every test
asserts on a returned/mutated value or an exception's type+message, never on
a mock call count alone (§3.2's "result driven" standard) —
`ArgumentCaptor` used for `StockLedgerEntry`/`LowStockThresholdCrossedEvent`
field assertions, `verify(x, never())` only for genuine negative guarantees.
- `StockPostingServiceUnitTest` (13 tests) — every case from §3.4-A (moving
  average incl. the zero-on-hand and no-unit-cost-fallback branches, both
  guards at their exact boundary, the reservation-fulfilling exemption) plus
  3 new low-stock-event tests (fires on the crossing, does NOT fire on a
  second low sale — proving edge- not level-triggered — does not fire on a
  virtual location).
- `CheckoutServiceUnitTest` (8 tests) — all of §3.4-B, including the
  per-line-rounding case (hand-verified: three lines of 333.33 at 16% VAT →
  45.98 each → 137.94 total, not a total-then-round 137.93).
- `StockReservationServiceUnitTest` (6 tests) — all of §3.4-C, including the
  null-vs-past-vs-exactly-now expiry semantics.
- `PaymentServiceUnitTest` (8 tests, not sketched in the plan — designed
  from the real guard logic) — idempotent-on-SUCCESS, throws-on-terminal-
  state, skips items with no reservation, and the C2 corruption scenario
  restated as a unit-level guarantee (`markFailedWhenAlreadySucceededThrowsAndNeverReleasesStock`).
- **§3.7's `Clock` injection** done exactly as specified: a `ClockConfig`
  bean (`Clock.systemUTC()`), threaded into the constructors of all four
  `Instant.now()` call sites named in the plan
  (`StockPostingService`/`CheckoutService`/`StockReservationService`/`PaymentService`),
  tests use `Clock.fixed(...)`. No other `Instant.now()` call site touched
  (out of scope per the plan).
- Confirmed the promised inner-loop win: `./mvnw test -Dtest='*UnitTest'`
  needs no `DB_USERNAME`/`DB_PASSWORD` and no Postgres at all.

**§2 — the five backend events.** `common/event/` — five records
(`OrderPlacedEvent`, `PaymentConfirmedEvent`, `PaymentFailedEvent`,
`OrderFulfilledEvent`, `LowStockThresholdCrossedEvent`; IDs and scalars only,
never entities) and one `EventLoggingListener` with all five methods
explicitly `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
(written explicitly per the plan even though it's the default), synchronous,
log-only per §2.8 (no `@Async` — nothing does real I/O yet). Publishers added
to `CheckoutService.checkout`, `PaymentService.confirmSuccess`/`markFailed`,
`OrderFulfilmentService.fulfil`, and `StockPostingService.applyMovement`
(the low-stock check — edge-triggered exactly as §2.6 specifies, and
deliberately restricted to `location.isInternal()`, my own addition beyond
the plan's literal pseudocode: a virtual location like SUPPLIER going more
negative is the designed other side of every receipt, not a "running low"
event). **Live-proven, not just unit-tested**: the concurrency integration
test's log output shows `EventLoggingListener` actually firing
after-commit against real Postgres (`"Payment confirmed: order RACE-... paid
500.00 via MPESA..."`).

**§4 C16 — the two new ArchUnit rules**, `ControllerArchitectureTest`
(new file, `architecture` package): (1) a controller method that calls a DTO's
static `from(...)` must be `@Transactional`; (2) a mutating endpoint
(`@Post/@Put/@Patch/@DeleteMapping`) may not be gated only on `*_VIEW`
authorities. Landing rule 1 required adding `@Transactional`
(`readOnly = true` on reads) to 10 controller methods that had none —
`BrandController`/`CategoryController`/`BranchController`'s list/get/create
endpoints, `StockReservationController.reserve`, `CheckoutController.checkout`
— two of which (`CategoryDto.from` reading `Category.parent`,
`StockLocationDto.from` reading `StockLocation.branch`) turned out to touch
a genuinely lazy association. **Both rules proven empirically** the same way
session 4 proved the stock-write rule: temporarily reintroduced a real
violation of each (removed `@Transactional` from a direct-call site;
regated `confirmManual` back onto `PERM_PAYMENT_VIEW`) and confirmed the
build fails with the exact violation named, then reverted. **Known,
documented gap**: rule 1 only sees a *direct* `Dto.from(x)` call in a
method's own bytecode — a method *reference* (`.map(BrandDto::from)`,
several simple list endpoints in this codebase use this style) compiles to
an `invokedynamic` site the call-graph check doesn't attribute back to the
enclosing method, confirmed by testing `BrandController.list()` specifically.
Every current method-reference call site still got `@Transactional` added by
hand as defense in depth; see the test class's javadoc for the full note.

**§1.5 Gap 5 — the password encoder.** `SecurityConfig.passwordEncoder` now
returns `PasswordEncoderFactories.createDelegatingPasswordEncoder()` with
`setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder())` explicitly
set (required, not optional — without it, verifying any *existing* bare-BCrypt
hash throws "no PasswordEncoder mapped for the id null", which would have
locked out every account created before this fix, including the seeded
bootstrap SUPER_ADMIN). **Live-verified** against `nairobi_beauty_hub_dev`'s
real bootstrap admin account: logged in against its existing bare hash
(`$2a$10$...`, no prefix) — worked; called `change-password` — new hash
written as `{bcrypt}$2a$10$...`; logged in with the new password against the
new prefixed hash — worked; old password now correctly rejected (401).

**Migrations:** only one this session, `V11__payment_record_provider_reference_index.sql`.

**Dev-DB test artifacts, `nairobi_beauty_hub_dev` only** (not `_test`, not
production — no prod exists yet): the bootstrap SUPER_ADMIN's password was
reset (to a known value, for login testing, since the original
once-only-logged temp password from an earlier session was never recorded
anywhere) and then changed again via a real `change-password` call as part
of verifying the §1.5 fix — it is *not* whatever it was before this session,
and the value isn't written here on purpose. A handful of test
products/orders/payment records from the C1-C4 curl verification pass are
also sitting in that DB (`C1 Verification Lotion` etc.) — harmless, same as
every prior session's live-test leftovers, left in place rather than cleaned
up.

**Not done, and correctly out of scope for this session**: everything in
Tier 2 (Boot 3.3.4→3.5.x, RestClient timeouts, `REQUIRES_NEW` on the counter
generators, `@EntityGraph` for order reads, the exception taxonomy, actuator)
and Tier 3. The plan explicitly sequences the Boot bump *after* the unit-test
layer (done this session) so it has real tests validating it — that's the
natural next infrastructure item, whenever this project's next session picks
up Tier 2.

*(Editorial note added in session 9: C5/C6/C7/C8/C9 above were in fact
completed at some point between this session and session 9 — visible in the
actual source and confirmed live-working — but never got a HANDOFF.md entry.
If you're reading this in a future session: **this file can be stale relative
to the real code**; verify anything load-bearing against the actual source
before trusting a "not done" claim, the same standing rule this project has
always used for the plan/audit docs.)*

## Session 9 — Boot 3.5.16 bump, actuator/metrics, delivery-model alignment

Picked up after confirming (against actual source, not just this file) that
C5/C6/C7/C8/C9 were already done. Verified live end-to-end on
`nairobi_beauty_hub_dev`; full suite (46 tests, 11 classes) green throughout.

**Delivery model aligned with the frontend's 2026-09-15 rebrand decision**
(TumaBoda dropped entirely; the shop never charges/estimates a delivery fee —
customer pays the courier directly, confirmed by phone/WhatsApp). New
migration `V12__align_delivery_model_with_business_decision.sql`:
`FulfilmentType.DELIVERY_TUMABODA` removed from the enum and the DB `CHECK`
constraint (no existing rows used it — checked first); a new
`CHECK (delivery_fee = 0)` constraint makes what was previously just
`CheckoutService`'s convention a structural guarantee. **Live-verified**: a
`DELIVERY_COURIER` checkout still works (`deliveryFee: 0` in the response);
a `DELIVERY_TUMABODA` checkout is now correctly rejected.

**§1.2 — Boot 3.3.4 → 3.5.16** (latest 3.5.x patch, verified against Maven
Central's metadata; 3.5.x is supported through 2027-07-31, 4.0/4.1
deliberately not chosen — see the plan's §1.2). Two real breakages surfaced
by the bump, both confirmed via `javap` against the actually-resolved jars
before fixing (not guessed from release notes):
- **Spring Security 6.5.11**: `DaoAuthenticationProvider`'s no-arg
  constructor + `setUserDetailsService(...)` are now `@Deprecated`
  (confirmed via `javap -v`, byte-for-byte). Fixed:
  `new DaoAuthenticationProvider(staffUserDetailsService)`.
  `setPasswordEncoder(...)` is NOT deprecated — left as a setter.
- **`org.springframework.boot.web.client.ClientHttpRequestFactories`/
  `ClientHttpRequestFactorySettings`** (used by `DarajaPaymentGateway`'s §4
  C8 timeout fix) are deprecated-for-removal, replaced by
  `org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder`/
  `ClientHttpRequestFactorySettings` (`.defaults()` instead of `.DEFAULTS`,
  `ClientHttpRequestFactoryBuilder.detect().build(settings)` instead of
  `ClientHttpRequestFactories.get(settings)`).

**§1.4 — actuator + a real metric for F7.** Added
`spring-boot-starter-actuator` + `micrometer-registry-prometheus`; exposed
`health`/`info`/`metrics`/`prometheus` only (`/actuator/health` was already
in `SecurityConfig`'s permit-list from session 3 — it 404'd the whole time
because the dependency never existed until now). New
`StockConsistencyMetricsScheduler` runs `StockBalanceRebuildService.check()`
hourly (`app.stock.consistency-check-interval-ms`, default 3600000) plus
once at boot, publishing the mismatch count as a Micrometer gauge
(`nbh_stock_consistency_mismatches`) and `log.error`-ing detail on any
non-zero reading. **Live-verified**: `/actuator/health` → real `{"status":
"UP"}` (not a 404); `/actuator/metrics` → 403 without a token (deliberately
gated — no real Prometheus scrape infra exists yet, revisit with a dedicated
scrape credential/management port when it does); the gauge, read
authenticated, correctly showed `0.0`.

**A real bug found live, from the C7 refactor** (the exception-taxonomy work
done in the untitled interim session): `GlobalExceptionHandler`'s new
`@ExceptionHandler(Exception.class)` fallback is *more specific than nothing*
but it shadows several of Spring's own default MVC exception mappings, since
`@RestControllerAdvice` handler selection picks the closest matching
`@ExceptionHandler` across ALL registered types — and a bare `Exception`
handler is still a match when nothing more specific is registered for, e.g.,
`HttpMessageNotReadableException`. Concretely: sending
`"fulfilmentType": "DELIVERY_TUMABODA"` (a value the enum no longer has,
found while testing the delivery-model change above) produced a raw 500
"unexpected error" instead of Spring's normal 400. **Found by live testing,
not by reasoning about the code** — exactly the standing lesson this project
keeps re-learning. Fixed by adding three explicit, more-specific handlers
ahead of the catch-all: `HttpMessageNotReadableException` → 400 (malformed/
invalid body — the one that actually broke), `MethodArgumentTypeMismatchException`
→ 400 (e.g. a non-UUID path segment), `HttpRequestMethodNotSupportedException`
→ 405 (wrong HTTP verb). **Live-verified all three** post-fix: invalid enum →
400, `GET /orders/not-a-real-uuid` → 400, `DELETE /checkout` → 405. This is
not necessarily an exhaustive list of what a bare `Exception.class` handler
can shadow — if a future session finds another standard Spring exception
coming back as a raw 500, this is almost certainly why; add the specific
handler rather than removing the fallback (which is still valuable for
genuinely unexpected exceptions and their correlation-id logging).

**Bootstrap admin password reset** on `nairobi_beauty_hub_dev` (the previous
session's value was deliberately never recorded, per its own note) —
new value shared out-of-band with the founder, not written here on purpose,
same convention as every prior reset. `must_change_password` is set.

**Not done this session, correctly deferred**: C10 (auth entry point/access-
denied-handler consistency — 401 vs 403), C12 (single `AvailabilityService`
definition — `CartService` and `StockReservationService` still compute
availability two different ways, narrowed but not eliminated by the F9
sweeper), C14 (stock request DTOs still use `String` + manual `BigDecimal`
parsing rather than typed/validated fields). All of Tier 3.

## Session 10 — customer-facing catalogue, image upload, reviews, analytics

Picked up on the founder's explicit ask to build out inventory management
(product/variant/image/SKU detail, related products) and customer-facing
browsing/search — separately from the earlier M-Pesa-vs-Co-op/Family-Bank-STK
question, which was researched and closed first (see below). Full test suite
(46 tests, 11 classes) stayed green throughout; every new endpoint was also
live-verified against `nairobi_beauty_hub_dev` with real curl calls, the same
standard every prior session has held itself to.

**Co-op/Family Bank STK push — researched, not built.** Direct integration
with either bank's own developer portal has no clear self-service path
publicly documented (sandbox testing and credential generation, but no stated
KYC/approval timeline). A payment aggregator (PayHero — already used on
Jania Hub — or Tuma Payment Solutions) can trigger M-Pesa STK push straight
into an *existing* Co-op/Family Bank paybill/till, self-service, "minutes not
months," once Trendysh actually has one of those. Decision: don't build
direct-to-bank integration; when real online payment is wanted, wire through
PayHero the same way Jania Hub does. `PaymentMethodId.bankStk` on the
frontend was relabelled generic "Bank STK push" (no bank named) to match —
see the frontend's own HANDOFF for that change.

**What was already there vs. what's new**: the attribute/variant/image/SKU
schema (`V2__catalogue.sql`, session 0) and the staff-authenticated
create/list/get product API (`ProductService`/`ProductController`, session 3)
already existed and needed no redesign — the founder's "attribute-based,
Odoo/ERPNext-style variants" request was already how this schema works.
Session 10 added the pieces genuinely missing:

- **`V13__product_reviews_and_analytics_events.sql`** — three new tables:
  `product_review` (verified-purchase reviews, PENDING until staff moderate),
  `product_sale_event` and `product_view_event` (append-only fact tables,
  deliberately not extending `BaseEntity` — no `updated_at`, same posture as
  `CustomerOrderItem`).
- **The public storefront read API**, `CatalogController` at
  `/api/v1/catalog/**` (added to `SecurityConfig`'s permit-list — no auth):
  `GET /products` (search `q` + `category` slug + pagination),
  `GET /products/{slug}` (full detail: images, variants with resolved
  attribute values/swatches, rating aggregate, units sold),
  `GET /products/{id}/related`, `GET /categories`,
  `POST /products/{id}/view` (records a `ProductViewEvent` — the "clicks"
  figure), `GET`/`POST /products/{id}/reviews`. Every read here only ever
  returns published, non-deleted products — `CatalogService`'s javadoc
  explains why this is a deliberately separate class from the staff-only
  `ProductService`, not a reused one with an `if (isStaff)` branch.
- **Verified-purchase reviews** (`ProductReviewService`) — since checkout is
  guest-only, eligibility stands in for a customer login: the caller must
  name a real order (`orderNo` + the `guestPhone` it was placed with) that
  actually contains the product, in a status proving payment succeeded
  (`PAID` or later — `DELIVERED` is modelled but not yet reachable, since
  order status still only moves `PENDING_PAYMENT → PAID → DISPATCHED`, per
  session 6's note). One review per order per product
  (`UNIQUE (order_id, product_id)`). Staff moderate via
  `AdminReviewController` (`/api/v1/admin/reviews/{id}/approve|reject`,
  `PERM_PRODUCT_MANAGE` — no new permission needed). **Live-verified**: a
  real guest order taken through checkout → manual payment confirmation →
  review submitted → blocked correctly on wrong phone (404, vague on purpose
  to avoid order-number enumeration) and on a second attempt with the same
  order (409) → approved by staff → appears in the public reviews list and
  the product detail's `avgRating`/`reviewCount`.
- **Sales analytics** (`ProductAnalyticsListener`, its own class in the
  catalog package per `EventLoggingListener`'s own javadoc convention) —
  listens to the *existing* `PaymentConfirmedEvent` (not `OrderPlacedEvent`)
  specifically so an abandoned/unpaid order never counts as a sale, and
  writes one `ProductSaleEvent` per order line. Failures are caught and
  logged, never rethrown — losing an analytics row must not turn an
  already-successful payment confirmation into a 500. **Live-verified**: a
  real manual payment confirmation produced exactly one sale event, reflected
  immediately in the product detail's `unitsSold`.
- **Cloudinary signed upload** (`CloudinarySignatureService` +
  `UploadController` at `/api/v1/admin/uploads/cloudinary-signature`,
  `PERM_PRODUCT_MANAGE`) — deliberately no Cloudinary SDK dependency; the
  signing algorithm (sort params, SHA-1 the joined string + secret) is public
  and small enough for a plain `MessageDigest`. The frontend asks this
  endpoint for a signature, uploads straight to Cloudinary with it, then POSTs
  the resulting `secure_url` to the new `POST /api/v1/products/{id}/images`
  (staff-only) to attach it — Cloudinary credentials never reach the
  frontend. New `app.cloudinary.*` env-var placeholders in `application.yml`,
  same pattern as `app.mpesa.*`; **not yet live-testable** — no real
  Cloudinary account exists for Trendysh yet, so only the "not configured"
  error path was verified (correctly a clean `ConfigurationException`, not a
  raw 500 — consistent with how `CheckoutService`'s missing-default-branch
  case is handled).
- **`ProductService.setPublished`** (`POST /products/{id}/publish|unpublish`)
  — products are created unpublished (staff finish photos/pricing first); this
  was the missing "go live" action, since nothing previously flipped that flag
  after creation.

**Two real bugs found live** (neither would have been caught by compiling or
by the existing unit-test suite, since both only manifest against a real
Postgres with real data):
1. **An implicit JPQL path navigation through a nullable association silently
   drops matching rows.** `ProductRepository.searchPublished`'s original form
   wrote `p.subcategory.slug = :categorySlug` directly in the WHERE clause;
   Hibernate compiles that as an INNER JOIN to `category` for the subcategory
   path, which excludes every product whose `subcategory` is null — the
   common case — even when its `category` matches and `categorySlug` is also
   null (so neither OR-branch should even apply). Found live: a published
   test product with a category set and no subcategory was invisible to
   `GET /catalog/products` with zero filters. Fixed with explicit
   `LEFT JOIN p.category c` / `LEFT JOIN p.subcategory sc` and comparing
   against the joined aliases. **This is a bug class worth watching for
   anywhere else in this codebase a nullable to-one association is navigated
   directly inside a WHERE/SELECT clause rather than joined explicitly.**
2. **A null bind parameter reused inside `LOWER(CONCAT(...))` crashes with a
   PostgreSQL type-inference error**, not a Hibernate-level one — `function
   lower(bytea) does not exist`. With `q` null, the JDBC driver can't infer a
   type for that parameter from its position alone and defaults to `bytea`.
   This broke *every* browse call with no search term, not just ones that
   happened to search — found live because the "no filters" smoke test still
   500'd after fix #1 above. Fixed by explicitly casting:
   `LOWER(CONCAT('%', CAST(:q AS string), '%'))`. **Worth checking any other
   optional-text-search JPQL query in this codebase for the same pattern.**

**Not done this session, correctly deferred**: full-text search (current
search is `ILIKE`-equivalent on name/short description — fine at hundreds of
products, would need a `tsvector`/GIN index or trigram extension at real
scale), a `DELIVERED` order-status transition (reviews are gated on `PAID` or
later since `DELIVERED` is unreachable today), rating denormalisation onto
`product` (computed live via `AVG`/`COUNT` on every read — fine at this
volume, would need caching at scale), and wiring the frontend's admin product
screens to any of this (frontend stays 100% mock/localStorage per the
answered scoping question — that's its own follow-up).

## Session 11 — integration readiness Phase 1 (backend gap-closing)

Picked up the plan at `C:\Users\ADMIN\.claude\NairobiBeautyHub_Integration_Readiness_Plan.md`
right after it was written. Full suite: 50 tests (4 new, in a new
`OrderFulfilmentServiceUnitTest`), all green; every new endpoint also
live-verified against `nairobi_beauty_hub_dev` with real curl calls,
including the negative cases (double-cancel, cancel-after-dispatch,
unauthenticated access).

**1.1 — Staff order list.** `GET /api/v1/orders` (paginated, optional
`status` + `q` search across order number/email/phone — `CustomerOrder`
doesn't capture a customer name today, so name search isn't possible yet;
flagged in the plan, not fixed here, since it's a checkout-request schema
change that belongs with Phase 4's checkout wiring, not this gap-closing
pass). New `OrderSummaryDto` + `CustomerOrderRepository.search`, a
constructor-expression query using `SIZE(o.items)` for the item count so
listing orders is one query, not one-plus-N (the F12 pattern).

**1.2 — Staff order cancellation.** The frontend's admin orders screen
assumed a cancel action existed; it didn't — orders could only move forward
(`PENDING_PAYMENT → PAID → DISPATCHED`). Added
`OrderFulfilmentService.cancel` (only PENDING_PAYMENT/PAID are cancellable —
anything DISPATCHED or later has physically left the building and needs a
real return process, not a status flip), releasing any ACTIVE reservation
and publishing a new `OrderCancelledEvent` through the existing event-log
seam. `POST /api/v1/orders/{id}/cancel`, `PERM_ORDER_MANAGE_ALL`.
**Live-verified**: cancelling a real PENDING_PAYMENT order → 200 CANCELLED;
cancelling an already-DISPATCHED order → 422 with a clear reason; cancelling
the same order twice → 422 the second time too.

**1.3 — Staff inventory browse.** `GET /api/v1/stock/inventory`
(`branchId`/`belowReorderOnly`/`q` filters, paginated) — `GET /stock/balance`
required already knowing a variant or location ID; there was no way to just
browse stock levels across the catalogue. New `InventoryLineDto` +
`StockBalanceRepository.browseInventory`, restricted to internal (physical)
locations only — a virtual location's balance (SUPPLIER, always negative;
CUSTOMER, always positive) is the double-entry mechanism, not something a
human should see in an inventory list.

**1.4 — Admin analytics.** New top-level `analytics` package (deliberately
not folded into `catalog` or `order` — it reads across both domains plus
stock). `AnalyticsController` behind `PERM_ANALYTICS_VIEW` (defined since
session 2, never checked anywhere until now):
`GET /api/v1/admin/analytics/summary` (revenue, order count, orders-by-status,
low-stock count) and `GET /api/v1/admin/analytics/top-products`. Revenue only
counts orders whose payment actually succeeded (PAID or later) — same
"confirmed, not merely placed" principle `ProductAnalyticsListener` already
applies to per-product sale events.

**Two more instances of session 10's null-parameter/PostgreSQL bug class,
different shapes each time** — worth reading closely if writing another
optional-filter JPQL query in this codebase:
1. A null `Instant` parameter (`since`, for "orders since X") reused in a
   plain `o.createdAt >= :since` comparison alongside an untyped
   `:since IS NULL` check in the same statement failed PostgreSQL's
   parameter type inference outright ("could not determine data type of
   parameter") — this is a *different* failure from session 10's
   `LOWER(CONCAT(...))` case, and critically, **the same `CAST(:since AS
   timestamp)` fix that worked for the string case did NOT work here** —
   it produced a new error, "cannot cast type bytea to timestamp without
   time zone," because the JDBC driver had already bound the null parameter
   as `bytea` before the SQL-level cast ever ran. Fixed differently:
   removed the null case from the query entirely and pushed it to the
   caller — `AnalyticsController` now defaults a missing `since` to
   `Instant.EPOCH` ("since the beginning of time") before calling the
   repository, so the query parameter is never null in the first place.
   **This is probably the more robust fix in general** — worth applying
   to future optional-date-filter queries instead of reaching for CAST.
2. Same fix applied pre-emptively to `ProductSaleEventRepository.topSellers`
   (same `since`-reused-in-comparison shape) before it ever broke in
   production, since the pattern was now recognised on sight.

**Not done this session, correctly deferred**: everything in the
integration readiness plan's Phase 2 onward (frontend real auth, then
wiring) — Phase 1 was backend-only by design so Phase 2+ has a stable API
surface to build against.

## Session 11b — Phase 5 backend prerequisites (2026-09-18)

Building the real admin product UI exposed backend gaps, closed here (full
suite 50/50 green; each endpoint curl-verified against `nairobi_beauty_hub_dev`):
- **Attributes API** — `GET/POST /api/v1/attributes`, `POST /attributes/{id}/values`
  (no `ProductAttributeRepository` or controller existed at all before).
- **Enriched staff `ProductDto`** — now carries `categoryName`, `brandName`,
  `primaryImageUrl`; list and detail both batch-fetch images (no N+1).
- **Product mutation endpoints** — `PATCH /products/{id}`, `POST /products/{id}/variants`,
  `PATCH /products/{id}/variants/{variantId}`. The original API could only create a
  product with its variants in one shot — there was no way to add a variant later,
  which was the founder's explicit original requirement. SKU is not editable.
- **CORS** default allow-list now includes port 5250 (the dev-preview port).

**Still missing, found while doing this:** the public catalog API has no
`inStock`/availability field (needed for storefront wiring, Phase 3.1); no
product/category delete or category rename endpoints.

**Environment gotcha:** launching `mvn spring-boot:run` through the harness's
background mode repeatedly died shortly after a clean boot (no error in the
log). Launch it detached instead: `nohup ./mvnw spring-boot:run > log 2>&1 < /dev/null & disown`.

## Session 11c — public catalogue availability (2026-09-18)

`inStock` added to `CatalogVariantDto`, `CatalogProductSummaryDto` and
`CatalogProductDetailDto` (browse, detail, related). Computed from sellable
quantity (on-hand minus reserved) across **physical** locations via one
batched `StockBalanceRepository.availabilityForVariants` query per page;
strictly positive counts, a product is in stock if any variant is, and
customers only ever see the boolean. Needed by the storefront (frontend Phase
3.1). 2 new unit tests (`CatalogServiceInStockUnitTest`), suite 52/52 green,
verified live against a product with one stocked and one unstocked variant.
Note it counts stock across ALL branches' physical locations, matching the
"one shared catalogue" decision; if branch-specific availability is ever
wanted, this is the place to change.

## Session 11d — staff order detail + recording how a customer paid (2026-09-18)

Needed by the real admin orders screen (frontend Phase 3.2/3.3). Suite 54/54.
- **`GET /api/v1/orders/{id}` now returns `AdminOrderDetailDto`** (was the
  shared `OrderDto`): guest email/phone, delivery address, created/placed
  timestamps, line items with **product and variant names**, and the
  **payment records** (staff need the payment id to confirm it). `OrderDto`
  is unchanged — it still backs guest tracking and the checkout response and
  deliberately doesn't expose payment ids. `CustomerOrderRepository.findById`'s
  entity graph now also fetches `items.variant.product`, so naming lines costs
  no extra queries (asserted in `OrderReadQueryCountTest`).
- **`confirm-manual` accepts an optional `method`** (`MPESA`/`BANK`/`CASH`).
  Checkout always creates the payment record as MPESA, but under the
  pay-after-we-call flow the customer may pay by bank or cash; without this
  the record would say MPESA forever. `PaymentService.confirmSuccess` gained a
  4-arg overload (the 3-arg one, used by the Daraja callback, is unchanged and
  delegates with `null`). 2 new unit tests.
- Live-verified: real order taken PENDING_PAYMENT → confirm (BANK, reference) →
  dispatched through the UI; analytics revenue and top-seller counts moved
  accordingly.

*(The customer-name gap noted here was closed in session 12.)*

## Session 12 — storefront checkout support (frontend Phase 4) (2026-09-19)

Everything the real storefront cart/checkout/tracking needed. Suite 63/63
(+9 tests: `CartServiceUnitTest` ×3, `PhoneNumbersTest` ×3, three new cases in
`CheckoutServiceUnitTest`).
- **V14 migration**: `customer_order.customer_name` (VARCHAR 200) and
  `delivery_method` (`BODA`/`VEHICLE`, CHECK-constrained). Both nullable —
  older orders have neither, and pickup has no delivery method.
  `CheckoutRequest` now requires `customerName` (@NotBlank, ≤200) and takes an
  optional `deliveryMethod`; the method is dropped for `BRANCH_PICKUP`. The name
  is trimmed. Email stays **required** (founder decision: tracking is order no. +
  email).
- **Admin surfaces**: `OrderSummaryDto` / `AdminOrderDetailDto` carry
  `customerName` (+ `deliveryMethod` on detail); order search now also matches
  the customer name.
- **Phone numbers are normalised** (`common/util/PhoneNumbers`): stored as
  `2547XXXXXXXX` (also the form the M-PESA API wants). Found because review
  eligibility compared the phone by exact string, so "0720 000 111" at checkout
  and "0720000111" on the review form would not match. Reviews now compare
  normalised (old rows stored raw still match). Admin phone search uses a
  fragment (`searchFragment`: leading 0 dropped) so "0720…" finds "254720…".
- **Cart safety fixes**:
  - `session_id` is unique, so a CONVERTED cart used to be re-used as-is under
    the same session (old lines reappearing, and a second checkout could
    duplicate the order). `CartService` now empties and reopens a closed cart;
    checkout takes a **pessimistic row lock** on the cart and refuses a cart that
    is not ACTIVE with **409** ("already checked out") — a double-submit can no
    longer create two orders/holds.
  - `CartItemDto` now exposes `productId` + `productSlug`; `findByCartId`
    fetches variant+product in one query (entity graph).
- `OrderItemDto` now includes `productName`/`variantName` so confirmation and
  guest tracking can show real names instead of SKUs.
- **Known, not fixed:** two *simultaneous* first requests for a brand-new cart
  session can both try to create the row (unique violation → 409). The frontend
  serialises adds to avoid it; a server-side `INSERT … ON CONFLICT` would make
  it airtight. The add-to-cart availability check compares the requested qty,
  not existing-line + requested (checkout's reservation is the real guard).
- **Product gallery (added later the same day, suite now 70/70, +7 tests in
  `ProductImageGalleryUnitTest`)**: several images per product with exactly one
  primary. `GET /products/{id}/images` (staff; cover first),
  `PUT /products/{id}/images/{imageId}/primary`. `attachImage`: a product's first
  image is *always* primary; a new primary demotes the old; sort order is
  max+1 (removals leave gaps). `removeImage` promotes the next image when the
  primary is removed, so a gallery never has images but no cover (the storefront
  list shows only the cover). Both reject an image that belongs to another product.
- Live-verified against the dev DB: add/merge, refusal above availability,
  validation errors (blank name, bad method), checkout, double-checkout 409,
  reusable empty cart afterwards, track with right/wrong email, stored row
  (name, normalised phone, method), admin search by name / phone fragment /
  order no.

## Session 13 — customer accounts, backend (backlog item 7) (2026-09-19)

Suite: the 70 from session 12 + 9 new (`LoginThrottleTest` 3, `CustomerAccountsIntegrationTest` 6).
Migration **V15** (`customer_account`, `customer_email_token`, `customer_order.customer_account_id`).
- **Separate from staff.** `customer/` package; `CustomerAccount` implements
  `UserDetails` with the single authority `ROLE_CUSTOMER`. JWTs now carry
  `kind` = STAFF/CUSTOMER (missing = STAFF, so old tokens still work) and
  `JwtAuthFilter` loads the matching table, so a customer email can never resolve
  to a staff account. `CustomerAccountLoader` is deliberately not a
  `UserDetailsService` bean. The filter also rejects disabled accounts now.
- **SecurityConfig:** `/api/v1/auth/**` is no longer wholesale public — only
  `/auth/login` is (me/change-password need a staff token). Public customer paths:
  register, login, verify-email, forgot-password, reset-password. `/api/v1/customer/**`
  needs `ROLE_CUSTOMER`; and **`anyRequest` now refuses `ROLE_CUSTOMER`**, so a
  forgotten-unprotected endpoint can't leak to shoppers.
- **Endpoints** (`/api/v1/customer/auth`): register, login, verify-email,
  forgot-password, reset-password, resend-verification, GET/PATCH me,
  change-password; `/api/v1/customer/orders` (mine) and `/{orderNo}` (404 for
  anyone else's). Passwords 8-72 chars (BCrypt's limit). Sign-in failures are
  generic; unknown emails do a dummy hash check; `LoginThrottle` locks an email
  for 15 min after 5 failures (in-memory, single-instance — 429).
- **Guest orders join an account only after email verification** (or a completed
  password reset — both prove mailbox control). Linking on registration alone
  would let anyone read a stranger's past orders. `CustomerOrderRepository.attachGuestOrders`.
  Orders placed while signed in attach immediately (`CheckoutController` reads the
  optional Bearer principal).
- **Email:** new `common/email` — `EmailSender`, `LoggingEmailSender` (default:
  the link is only logged — dev) and `BrevoEmailSender` (when `BREVO_API_KEY` +
  `MAIL_FROM_EMAIL` are set; **untested against the live API**, no key available).
  Sent AFTER COMMIT via `CustomerEmailRequestedEvent`. Config: `app.frontend-url`
  (`FRONTEND_URL`, for the verify-email link), `app.mail.*`. Only the SHA-256 hash is ever
  stored, single use, 2-minute resend cooldown. Verify-email is still a 32-random-byte link,
  48h TTL. Password reset is now a 6-digit OTP (see the dated entry at the top of this
  file), 10-minute TTL, 5-attempt lockout.
- `OrderDto` gained `createdAt`.
- **Before deploying:** set `BREVO_API_KEY`, `MAIL_FROM_EMAIL`, `FRONTEND_URL`, a real `JWT_SECRET`.

## Next session — start here (backend)

Phases 1-6 of the integration plan are done. **Session 12's work is uncommitted**
(last commit `2e7545e`); review `git status`, commit with **no AI attribution**,
push. Suite: 70/70 (recreate `nairobi_beauty_hub_test` first; dev DB is
`nairobi_beauty_hub_dev`, password `janet-marketing`; V14 is the latest migration).
Open backend items: `INSERT … ON CONFLICT` for concurrent first cart requests;
add-to-cart availability ignores the existing line qty (checkout's reservation is
the real guard); no product delete / category rename endpoints; real Cloudinary
upload untested (no account); backend has no public deployment yet (blocks the
deployed staging frontend). Backlog 7-9 in the plan (customer accounts, visit
analytics, promo banners) is next after deployment.

## Deliberately not built yet

Refresh tokens, password-reset flow, POS, inter-branch transfers (the
two-leg IN_TRANSIT flow from the design doc §4.6 — `StockDocumentType.TRANSFER`
exists in the schema but no endpoint posts one yet), delivery fee calculation,
TumaBoda dispatch integration, customer accounts/login (checkout is
guest-only), notifications (order confirmation emails/SMS), promo codes,
delivery zones, direct bank (Co-op/Family Bank) STK push integration
(researched session 10 — not straightforward directly, planned via a payment
aggregator like PayHero instead, once Trendysh has a paybill with the bank).

## Before continuing

The design doc §8 lists 14 open questions; five block Phase 1 proper (payment
provider, shared-vs-branch catalogue, cart reservation TTL, which branch
fulfils an online order, negative-stock policy). This scaffold used the
doc's own recommended defaults where one was given (one catalogue/per-branch
stock, checkout-time reservation, hard-blocked negative stock) — confirm or
override before building the e-commerce layer on top.
