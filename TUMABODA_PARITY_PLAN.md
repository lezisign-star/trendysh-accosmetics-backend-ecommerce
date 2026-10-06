# TumaBoda parity plan

Working plan for bringing Trendysh's TumaBoda checkout, orders-management and payments-management
up to Moments' level (or better), built while real TumaBoda credentials are still pending. Written
against the code as it stands on 2026-09-30 (backend `main` @ `70fdb1c`, frontend `staging` @
`7854371`). Update this file as decisions change — it's the thing we build from, not a one-time memo.

Reference codebase: `J:\backend\moments-packaging-backend-Java-First client` and
`J:\frontend\moments-non-lovable-project`. Moments is mature and battle-tested, but it solves for
Moments' own commercial arrangement with TumaBoda — copy the *shape*, not the specifics, wherever
Trendysh's situation differs.

## Already in place — don't rebuild this

The backend has more TumaBoda plumbing than the earlier gap-analysis gave credit for. Confirmed by
reading the actual code, not assumed:

- **Per-order API booking**: `CustomerOrder` already has `dropoffLat/Lng`, `tumabodaDeliveryId`,
  `tumabodaDeliveryNumber`, `tumabodaTrackingCode/Url`, `tumabodaStatus`, `tumabodaRiderId`,
  `tumabodaLastWebhookAt` (`CustomerOrder.java:85-118`).
- **Cost vs. price already separated per order**: `tumabodaCost` ("what TumaBoda charged the shop")
  is already a distinct column from `deliveryFee` ("what the customer paid, the quote at checkout") —
  `CustomerOrder.java:110-112`. This is exactly the distinction a settlement ledger needs; it already
  exists at the row level, just not aggregated anywhere yet.
- **Booking retry, reroute, restart**: `tumabodaBookingAttempts`, `tumabodaNextRetryAt`,
  `tumabodaBookingFailure`, `tumabodaRestartCount`, `tumabodaRerouted`, `isDeadTumabodaStatus(...)`,
  `hasLiveTumabodaDelivery()` all exist and are wired into staff actions (book / restart / reroute to
  manual / check now) in `admin.orders.tsx`.
- **Per-order "needs attention"**: `AdminOrderDetailDto.needsAttention` is already computed
  (`AdminOrderDetailDto.java:47-63`) and rendered with a destructive-red border in the `TumabodaPanel`
  on the order detail view (`admin.orders.tsx:755-758`). What's missing is *aggregate* visibility
  across the order book (see Section D) — today staff only see it by opening each order.
- **Checkout address search**: debounced autocomplete, GPS "use my location", quick-pick chips for
  seeded common areas, live per-point quote with graceful "arrange by phone" fallback
  (`TumabodaAddressPicker.tsx`, `V41__common_delivery_areas.sql`).
- **"Coming soon" pattern to copy for Section E**: `admin.settings.tsx:407-427` — a disabled
  `<li aria-disabled="true" className="opacity-50">` list item with a grey "Coming soon" pill next to
  the title. Reuse this exact treatment for TumaBoda, don't invent a new one.

## Decisions locked in this conversation

1. **Checkout address entry**: merge the two separate address-entry moments (Step 2's free-text
   Area/estate field, then TumaBoda's own autocomplete search) into one. See Section A.
2. **Delivery-contact phone**: add a phone field for "who the rider should call at drop-off," distinct
   from the account/order phone used for M-Pesa. Autofills from the existing payment/order phone but
   stays independently editable. See Section A.
3. **Settlement model**: build the Moments-style ledger. Customer-facing collection is unchanged —
   the fee is still bundled into the order total and charged upfront via M-Pesa, exactly as today.
   What's new is purely a backend/admin concern: Trendysh accumulates what it owes TumaBoda per
   delivery (TumaBoda is the one calculating/quoting the fee) and settles that balance periodically
   by bank transfer, rather than per-order in real time. See Section B.
4. **Pickup OTP**: add the plumbing now (field + staff-facing display), even though it will be
   unused/blank until real TumaBoda credentials confirm the API actually returns it. See Section C.
5. **Aggregate ops dashboard**: build it in this pass, not deferred. See Section D.
6. **Quote "mode" (UPFRONT/POD)**: not applicable. Confirmed by reading
   `PublicTumaBodaQuoteController.java:61-92` — this is Moments' own admin toggle
   (`tumaboda.pod.enabled`) for whether the rider collects cash at the door instead of the fee being
   charged electronically. It's not a TumaBoda API capability question, and Trendysh doesn't need it
   unless a future "pay the rider cash" option is specifically wanted. No code needed for this.
7. **Fade to "Coming soon"**: once Sections A-D are built and verified, apply the pattern from
   `admin.settings.tsx` to grey out TumaBoda as a selectable option in both checkout and admin
   orders/orders-management — not fully hidden, matched to how the Co-op Bank card already reads.
   This is explicitly the *last* step, pending real TumaBoda credentials. See Section E.

## Explicitly out of scope

- **Group / multi-stop rider booking.** Moments has this; it's a nice-to-have that doesn't matter at
  Trendysh's current order volume. Revisit post-launch if it ever becomes relevant. Not asked about
  directly — flagging here so it isn't silently assumed in scope. Say so if this assumption is wrong.

## Section A — Checkout: merge the two address entries, add delivery-contact phone

Today (`checkout.tsx`), Step 2 ("Delivery") asks for County (dropdown) + Area/estate (free text) +
Building/landmark (free text) as the canonical order address — used for every delivery method. If the
customer then picks "TumaBoda (Rider)", a second, separate autocomplete widget
(`TumabodaAddressPicker`) appears below asking them to search their address *again*, this time to
resolve exact coordinates for pricing/dispatch.

**Target design**: when TumaBoda is the delivery method, the autocomplete search *is* the address
field — no separate free-text Area/estate entry for that path. Concretely:

- Matatu / Courier / Pickup keep the existing free-text County + Area/estate + Building/landmark form
  exactly as today — they don't need geocoding.
- TumaBoda replaces Area/estate + Building/landmark with the existing `TumabodaAddressPicker` UI
  (search / GPS / quick-picks), moved up to where the address form lives in the step, not
  down in the separate "Delivery method" section. County stays (still useful as a coarse fallback and
  for the address-on-file even for phone-arranged bookings).
- The resolved `TumabodaChoice.description` becomes the stored address text for the order (what
  matatu/courier customers type by hand, TumaBoda customers get from the picked suggestion/GPS
  result instead) — one field, populated two different ways depending on method.
- Practical effect: choosing TumaBoda earlier in the step (before reaching "Delivery method") makes
  more sense with this design — worth trying the method choice *above* the address block, so the
  right address widget renders based on the method already chosen, rather than showing a free-text
  field that then gets replaced. Confirm this reordering reads well once mocked up; it's a UX call, not
  a hard requirement.
- This also gives coverage a slightly earlier signal for free — as soon as an address is resolved (now
  the *only* address step for a TumaBoda order), the existing live quote fires, same as today.

**Delivery-contact phone**: add a new field, e.g. `deliveryContactPhone` — likely sits naturally in
the same area as the address picker, since it's "who's at this drop-off point," not "who placed the
order." Autofill it from the existing checkout phone field the moment it has a value, but let the
customer overwrite it. Persist it on the order (new column) and send it as the `Recipient.phone` in
`TumaBodaDtos.DeliveryRequest` at booking time, instead of reusing the order's main phone — that's
the whole point of the field (a warehouse/gate guard receiving for a B2B buyer, a different person than
whoever placed the order and pays).

## Section B — Payments management: TumaBoda settlement ledger

New concept, doesn't exist today. Modeled on Moments' `TumaBodaSettlementService` /
`TumaBodaPayment` / `TumaBodaReconciliation`, adapted to Trendysh's actual need:

- **What's owed**: sum of `tumabodaCost` across orders where a TumaBoda delivery was actually booked
  (`hasLiveTumabodaDelivery()` or equivalent) and not yet marked settled. This is a query/view over
  existing data, not new per-order state — the raw numbers already exist on `CustomerOrder`.
- **New**: a `tumaboda_settlement` (or similar) table recording each remittance event Trendysh makes
  to TumaBoda — date, amount, method (bank transfer), reference/note, which orders' `tumabodaCost` it
  covers (either a join table or a `settled_at`/`settlement_id` column added to the relevant orders).
- **New UI**: a tab in `admin.payments.tsx` (it already uses the `Tabs` component for
  Awaiting/Claims/Credits — a "TumaBoda settlement" tab fits the existing pattern) showing: running
  balance owed, delivery-by-delivery breakdown feeding that balance, and a "record a settlement"
  action (amount + date + reference) that marks the covered deliveries settled.
  Migration: next available Flyway version is **V45** (V44 was taken by `wholesale_pricing` on 2026-09-30).
- **Reconciliation against TumaBoda's own balance**: Moments checks its ledger against TumaBoda's
  credit-line API. Can't build the real integration without credentials — stub the reconciliation
  service behind an interface now, wire the real HTTP call in once TumaBoda's docs/creds exist. Don't
  block the rest of Section B on this piece.

## Section C — Pickup OTP

- Add `pickupOtp` (exact field name TBD against real API docs — this is a placeholder name) to
  `TumaBodaDtos.Delivery` (`TumaBodaDtos.java:33`) and to `CustomerOrder` (new column, next migration
  slot after whatever Section B takes).
- Surface it in `TumabodaPanel` (`admin.orders.tsx`) next to the existing tracking code/rider ID
  display — something like "Give the rider this code: ####" for staff handling drop-off.
  Blank/hidden when null, exactly like the other optional TumaBoda fields already are.
- No behavior depends on it yet — it's display-only plumbing until TumaBoda's real API is live and
  confirmed to return it.

## Section D — Aggregate "needs attention" view

Today `needsAttention` exists only per-order (`AdminOrderDetailDto`) and only surfaces once staff open
that specific order. `OrderDto` (the list-level DTO used by `listOrders`) doesn't carry it at all —
confirmed by reading `OrderDto.java:16-36`.

Target, modeled on Moments' equivalent dashboard but scoped to what Trendysh's data actually supports:

- Extend the list query/DTO so `needsAttention` (and enough of `bookingFailure` context to show why)
  is available at the list level, not just per-order.
- Add a filter/count on the existing **Fulfilment** tab (`OrdersSubNav` currently has just
  Fulfilment + Payments) rather than a brand-new page — e.g. a "Needs attention (N)" chip alongside
  whatever filters already exist, reusing the destructive-red treatment already established in
  `TumabodaPanel`. Start here; only split into its own tab/page if the list actually grows unwieldy.
- Buckets worth flagging, mirroring Moments' shape:
  - Paid but never booked (checkout completed, no TumaBoda booking attempt succeeded or was retried
    into existence).
  - TumaBoda-reported dead (`isDeadTumabodaStatus`) without a reroute/restart yet actioned.
  - Dispatched but not confirmed delivered after some threshold. **Open detail to nail down while
    building**: confirm whether "delivered" in this codebase is ever customer-confirmed or always a
    staff/webhook-driven status change (`OrderStatus`/`OrderFulfilmentService` — worth a quick read
    when this section is actually built) — that determines whether "unconfirmed" means "no webhook
    yet" or something else entirely. Not resolved here; don't guess at a threshold number without
    checking actual dispatch-to-delivery timing on real past orders first.

## Section E — Fade to "Coming soon" (last step, gated on credentials)

Only once Sections A-D are built and verified locally:

- Checkout: in the delivery-method list (`checkout.tsx:580-609`), grey out the TumaBoda option the
  same way `admin.settings.tsx:407-427` greys out Co-op Bank — disabled-looking row, "Coming soon"
  pill, explanatory subtext — instead of removing it from the `methods` array entirely.
- Admin orders: staff-facing TumaBoda actions (book / restart / reroute / check-now) become
  unavailable the same way, with the same visual treatment, rather than the buttons just vanishing.
- This is a presentation-layer change, not a removal — all the Section A-D plumbing stays intact
  underneath so flipping it back on (once credentials arrive) is a small change, not a rebuild.

## Suggested build order

1. Section A (checkout merge + contact phone) — highest customer-facing value, and simplifies what
   Section D needs to reason about (one address path instead of two).
2. Section D (aggregate attention view) — cheap relative to B/C since the underlying per-order data
   already exists; mostly a list-DTO + small UI change.
3. Section C (pickup OTP) — small, isolated, no dependencies on the others.
4. Section B (settlement ledger) — largest of the four; do it last so the shape of "what counts as a
   booked/costed delivery" is already settled by A/D work rather than guessed at twice.
5. Section E (fade to Coming soon) — only after 1-4 are done and locally verified.

Ask before starting each section if anything above turns out to not match what's actually in the code
by the time we get to it — this plan is a starting point, not a spec to force the code to match.
