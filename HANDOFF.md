# Nairobi Beauty Hub / Trendysh Accosmetic Backend — Handoff

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

## CO-OP INS RECEIVER (2026-09-21, UNCOMMITTED) — built from the bank's PUBLIC spec, NEVER tested against the real bank
`POST /api/v1/payments/coop/ins` (`com.nbh.backend.payment.coop.*`): Basic-auth check (`COOP_INS_USERNAME/PASSWORD`,
blank = reject everything), records CREDIT events for `COOP_INS_ACCOUNT_NUMBER` via `BankCreditService.record(...,
IPN, autoMatch)`. **`COOP_INS_AUTO_MATCH` defaults to false**: credits are recorded for staff to allocate on
`/admin/payments`; turn on only after verifying against the real bank (INS has only a shared Basic credential, no
signature). Reference is searched in Narration + CustMemoLine1-3 (bank has not said which). Ack body shape is an
assumption. NOT built: AccountTransactions reconciliation (needs OAuth creds + sandbox), SafaricomSTKPush (see
Coop-STK-Push-Configuration-Guide.pdf in Downloads: ~30% chance of production access in 6 weeks; do not depend on it).
Suite on a FRESH DB: 139/140; the 1 failure is the known stale
`CustomerAccountsIntegrationTest.aShopperWhoRegistersWithAStaffEmailStillCannotBecomeStaff` (not from this work).


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
  (`FRONTEND_URL`, for the links), `app.mail.*`. Tokens: 32 random bytes, only the
  SHA-256 stored, single use, verify 48h / reset 1h, 2-minute resend cooldown.
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
