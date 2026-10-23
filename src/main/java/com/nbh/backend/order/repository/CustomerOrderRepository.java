package com.nbh.backend.order.repository;

import com.nbh.backend.order.dto.OrderSummaryDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    /**
     * §4 C5 fix: without this, {@code OrderDto.from} (iterating {@code
     * items} then, per item, {@code item.getVariant().getSku()}) incurs its
     * own N+1 query — 1 for the order, 1 for the items collection, N for the
     * variants — on every order read. Hit at three call sites:
     * {@code OrderController.get}/{@code .track} and
     * {@code OrderFulfilmentService.fulfil}. One query instead, via a LEFT
     * JOIN FETCH per attribute path.
     */
    @EntityGraph(attributePaths = {"items", "items.variant"})
    Optional<CustomerOrder> findByOrderNo(String orderNo);

    // items.variant.product: the staff detail view names each line's product;
    // fetching it here keeps that in the one query instead of one per line.
    @Override
    @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product"})
    Optional<CustomerOrder> findById(UUID id);

    /** Orders nobody has stopped the new-order ring for, oldest first. */
    @Query("select o from CustomerOrder o where o.alertAcknowledgedAt is null order by o.createdAt asc")
    List<CustomerOrder> unacknowledgedAlerts(org.springframework.data.domain.Pageable page);

    @Query("select count(o) from CustomerOrder o where o.alertAcknowledgedAt is null")
    long countUnacknowledgedAlerts();

    /** Stops the ring for every order that arrived up to {@code upTo}; a newer one keeps ringing. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update CustomerOrder o set o.alertAcknowledgedAt = :now, o.alertAcknowledgedBy = :by "
            + "where o.alertAcknowledgedAt is null and o.createdAt <= :upTo")
    int acknowledgeAlertsUpTo(@Param("upTo") java.time.Instant upTo, @Param("now") java.time.Instant now,
                              @Param("by") String by);

    /**
     * Phase 0 fix (2026-09-27), D2: every mutating call in {@code OrderFulfilmentService}
     * ({@code fulfil}/{@code startPicking}/{@code markReady}/{@code markDelivered}/
     * {@code cancel}) used to load the order unlocked before changing its status — two
     * concurrent staff actions on the same order (or a race against a customer-facing
     * cancel) could both pass the same status-guard check and both proceed. No
     * {@code @EntityGraph} here on purpose: Postgres rejects {@code FOR UPDATE} on the
     * nullable side of an outer join, and {@code items} is a collection fetch, not a
     * to-one join, so it must load as its own query after this lock, not joined into it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> lockById(@Param("id") UUID id);

    /**
     * An order's id from its number without loading the order. For callers that go on to lock the order:
     * loading it first would put a copy in the persistence context, and the lock that follows would hand
     * back that copy instead of what another transaction just committed.
     */
    @Query("select o.id from CustomerOrder o where o.orderNo = :orderNo")
    Optional<UUID> findIdByOrderNo(@Param("orderNo") String orderNo);

    /** Guest tracking by order number alone: the status and nothing else, so no entity is loaded. */
    @Query("select o.status from CustomerOrder o where o.orderNo = :orderNo")
    Optional<OrderStatus> findStatusByOrderNo(@Param("orderNo") String orderNo);

    /** Orders under a verified email, newest first ({@code email} already lower-cased). No line items are loaded. */
    @Query("""
            SELECT NEW com.nbh.backend.order.dto.TrackingDtos$TrackedOrderSummaryDto(
                o.orderNo, o.status, o.createdAt, (SELECT COUNT(li) FROM CustomerOrderItem li WHERE li.order = o AND li.parentItem IS NULL), o.grandTotal, o.fulfilmentType)
            FROM CustomerOrder o
            WHERE LOWER(o.guestEmail) = :email
            ORDER BY o.createdAt DESC
            """)
    List<com.nbh.backend.order.dto.TrackingDtos.TrackedOrderSummaryDto> findTrackedSummaries(@Param("email") String email);

    boolean existsByGuestEmailIgnoreCase(String guestEmail);

    /** TumaBoda bookings whose retry time has come — see {@code TumaBodaBookingRetryJob}. */
    @Query("select o.id from CustomerOrder o where o.tumabodaNextRetryAt is not null and o.tumabodaNextRetryAt <= :now "
            + "and o.tumabodaDeliveryId is null and o.tumabodaRerouted = false")
    List<UUID> findIdsDueForTumabodaRetry(@Param("now") Instant now);

    /**
     * The order a TumaBoda delivery id belongs to, if any (webhooks and status polls arrive keyed by it).
     * Id only, deliberately: the caller then takes the order's lock, and loading the entity first would
     * leave a stale copy in the persistence context for that lock to hand back.
     */
    @Query("select o.id from CustomerOrder o where o.tumabodaDeliveryId = :deliveryId")
    Optional<UUID> findIdByTumabodaDeliveryId(@Param("deliveryId") String deliveryId);

    /** A booked delivery not yet seen finishing, and quiet since {@code quietSince}: the reconciliation poll's worklist. */
    interface DeliveryToPoll {
        UUID getId();
        String getTumabodaDeliveryId();
    }

    @Query("""
            select o.id as id, o.tumabodaDeliveryId as tumabodaDeliveryId from CustomerOrder o
            where o.tumabodaDeliveryId is not null and o.tumabodaRerouted = false
              and (o.tumabodaStatus is null or lower(o.tumabodaStatus) not in ('delivered', 'failed', 'returned', 'cancelled', 'canceled'))
              and (o.tumabodaLastWebhookAt is null or o.tumabodaLastWebhookAt < :quietSince)
            """)
    List<DeliveryToPoll> findTumabodaDeliveriesToPoll(@Param("quietSince") Instant quietSince);

    /** Ids only — feeds {@code OrderExpiryService}, which then locks and re-checks each order itself. */
    @Query("select o.id from CustomerOrder o where o.status = :status")
    List<UUID> findIdsByStatus(@Param("status") OrderStatus status);

    /**
     * Unpaid orders due their one "we still need your M-PESA code" email: placed before {@code cutoff}, no reminder
     * sent yet, a payment window still open ({@code now} is before it ends), and not a single code sent for them.
     * Ids only: {@code OrderPaymentReminderService} locks and re-checks each one before it sends anything.
     */
    @Query("""
            select o.id from CustomerOrder o
            where o.status = com.nbh.backend.order.entity.OrderStatus.PENDING_PAYMENT
              and o.paymentReminderSentAt is null
              and o.isMockData = false
              and o.createdAt <= :cutoff
              and exists (select 1 from PaymentRecord p
                          where p.order = o and p.paymentReference is not null
                            and p.status in (com.nbh.backend.payment.entity.PaymentRecord.PaymentStatus.INITIATED,
                                             com.nbh.backend.payment.entity.PaymentRecord.PaymentStatus.PENDING)
                            and p.expiresAt > :now)
              and not exists (select 1 from PaymentClaim c where c.payment.order = o)
            order by o.createdAt asc
            """)
    List<UUID> findIdsDueForPaymentReminder(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /**
     * Orders this person already has waiting for payment, matched on phone OR email — the cap
     * on how many stock holds one contact can have open at once (see CheckoutService).
     */
    @Query("""
            select count(o) from CustomerOrder o
            where o.status = :status
              and (o.guestPhone = :phone or lower(o.guestEmail) = lower(:email))
            """)
    long countByStatusForContact(@Param("status") OrderStatus status,
                                 @Param("phone") String phone, @Param("email") String email);

    /**
     * Staff order-browse list — one query, no N+1 (F12 pattern): the item
     * count is a correlated-count subquery, not a fetch of the items
     * collection itself, and it counts what was bought (a kit once: the
     * products inside it are not counted again). {@code status}/{@code q} are optional; the
     * {@code CAST(:q AS string)} is required for the same reason
     * {@code ProductRepository.searchPublished} needed it — a null bind
     * parameter reused inside LOWER/LIKE otherwise defaults to bytea on
     * PostgreSQL and crashes every unfiltered call, not just filtered ones
     * (found live in session 10; same fix applied here pre-emptively).
     *
     * <p>The 28 {@code MOCK-}-prefixed orders {@code MockDataSeederService} creates (as review/
     * sale-event FK targets, never through real checkout) have no {@code isPublished} field of
     * their own to flip, unlike mock products/categories — so the final clause mirrors {@link
     * com.nbh.backend.catalog.repository.ProductRepository#searchAdmin}'s fix by checking the same
     * live signal {@code MockDataSeederService.status()} uses for "enabled" (any mock product
     * currently published), rather than a separate flag that could drift out of sync with it.
     */
    /**
     * {@code needsAttention} mirrors {@code AdminOrderDetailDto.Tumaboda.from}'s per-order logic
     * exactly (a booking's retries are exhausted, or TumaBoda reports the delivery dead, and staff
     * haven't rerouted it to a phone-arranged transporter) — Phase 3 (2026-10-02): extends that
     * existing signal to the list view instead of inventing new buckets. {@code needsAttentionOnly}
     * filters to just those when true; combines with {@code status}/{@code q} like any other filter.
     */
    @Query("""
            SELECT NEW com.nbh.backend.order.dto.OrderSummaryDto(
                o.id, o.orderNo, o.status, o.customerName, o.guestEmail, o.guestPhone,
                (SELECT COUNT(li) FROM CustomerOrderItem li WHERE li.order = o AND li.parentItem IS NULL), o.grandTotal, o.createdAt,
                CASE WHEN o.tumabodaRerouted = true THEN false
                     WHEN LOWER(COALESCE(o.tumabodaStatus, '')) IN ('failed', 'returned', 'cancelled', 'canceled') THEN true
                     WHEN o.tumabodaDeliveryId IS NULL AND o.tumabodaBookingFailure IS NOT NULL AND o.tumabodaNextRetryAt IS NULL THEN true
                     ELSE false END,
                o.tumabodaBookingFailure, o.fulfilmentType, o.deliveryAddress)
            FROM CustomerOrder o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:q IS NULL
                   OR LOWER(o.orderNo) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.customerName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR o.guestPhone LIKE CONCAT('%', CAST(:phoneQ AS string), '%'))
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:needsAttentionOnly = false
                   OR (o.tumabodaRerouted = false
                       AND (LOWER(COALESCE(o.tumabodaStatus, '')) IN ('failed', 'returned', 'cancelled', 'canceled')
                            OR (o.tumabodaDeliveryId IS NULL AND o.tumabodaBookingFailure IS NOT NULL AND o.tumabodaNextRetryAt IS NULL))))
              AND (:pickupPlace IS NULL
                   OR (o.fulfilmentType = com.nbh.backend.order.entity.FulfilmentType.PICKUP
                       AND LOWER(o.deliveryAddress) LIKE LOWER(CONCAT(CAST(:pickupPlace AS string), '%'))))
              AND (:paymentCode IS NULL
                   OR (CAST(:paymentCode AS string) = 'WITH_CODE'
                       AND (o.status IN (com.nbh.backend.order.entity.OrderStatus.PAID,
                                         com.nbh.backend.order.entity.OrderStatus.PICKING,
                                         com.nbh.backend.order.entity.OrderStatus.READY,
                                         com.nbh.backend.order.entity.OrderStatus.DISPATCHED,
                                         com.nbh.backend.order.entity.OrderStatus.DELIVERED)
                            OR EXISTS (SELECT 1 FROM PaymentClaim c WHERE c.payment.order = o)))
                   OR (CAST(:paymentCode AS string) = 'NO_CODE'
                       AND o.status = com.nbh.backend.order.entity.OrderStatus.PENDING_PAYMENT
                       AND NOT EXISTS (SELECT 1 FROM PaymentClaim c WHERE c.payment.order = o))
                   OR (CAST(:paymentCode AS string) = 'EXPIRED_NO_CODE'
                       AND o.status = com.nbh.backend.order.entity.OrderStatus.EXPIRED
                       AND NOT EXISTS (SELECT 1 FROM PaymentClaim c WHERE c.payment.order = o)))
            ORDER BY o.createdAt DESC
            """)
    Page<OrderSummaryDto> search(@Param("status") OrderStatus status, @Param("q") String q,
                                  @Param("phoneQ") String phoneQ, @Param("needsAttentionOnly") boolean needsAttentionOnly,
                                  @Param("pickupPlace") String pickupPlace, @Param("paymentCode") String paymentCode,
                                  Pageable pageable);

    /**
     * The fulfilment queue: only orders still waiting on staff ({@code statuses} — dispatched, delivered,
     * cancelled and expired ones are handled and drop out), oldest first so the longest-waiting is on
     * top. Same demo-data gating, search and needs-attention filters as {@link #search}.
     */
    @Query("""
            SELECT NEW com.nbh.backend.order.dto.OrderSummaryDto(
                o.id, o.orderNo, o.status, o.customerName, o.guestEmail, o.guestPhone,
                (SELECT COUNT(li) FROM CustomerOrderItem li WHERE li.order = o AND li.parentItem IS NULL), o.grandTotal, o.createdAt,
                CASE WHEN o.tumabodaRerouted = true THEN false
                     WHEN LOWER(COALESCE(o.tumabodaStatus, '')) IN ('failed', 'returned', 'cancelled', 'canceled') THEN true
                     WHEN o.tumabodaDeliveryId IS NULL AND o.tumabodaBookingFailure IS NOT NULL AND o.tumabodaNextRetryAt IS NULL THEN true
                     ELSE false END,
                o.tumabodaBookingFailure, o.fulfilmentType, o.deliveryAddress)
            FROM CustomerOrder o
            WHERE o.status IN :statuses
              AND (:q IS NULL
                   OR LOWER(o.orderNo) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.customerName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR o.guestPhone LIKE CONCAT('%', CAST(:phoneQ AS string), '%'))
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:needsAttentionOnly = false
                   OR (o.tumabodaRerouted = false
                       AND (LOWER(COALESCE(o.tumabodaStatus, '')) IN ('failed', 'returned', 'cancelled', 'canceled')
                            OR (o.tumabodaDeliveryId IS NULL AND o.tumabodaBookingFailure IS NOT NULL AND o.tumabodaNextRetryAt IS NULL))))
              AND (:pickupPlace IS NULL
                   OR (o.fulfilmentType = com.nbh.backend.order.entity.FulfilmentType.PICKUP
                       AND LOWER(o.deliveryAddress) LIKE LOWER(CONCAT(CAST(:pickupPlace AS string), '%'))))
            ORDER BY o.createdAt ASC
            """)
    Page<OrderSummaryDto> searchQueue(@Param("statuses") java.util.Collection<OrderStatus> statuses,
                                      @Param("q") String q, @Param("phoneQ") String phoneQ,
                                      @Param("needsAttentionOnly") boolean needsAttentionOnly,
                                      @Param("pickupPlace") String pickupPlace, Pageable pageable);

    /** The "Needs attention (N)" chip's count — same predicate as {@code search}'s needsAttentionOnly branch, unfiltered by status/q. */
    @Query("""
            SELECT COUNT(o) FROM CustomerOrder o
            WHERE o.tumabodaRerouted = false
              AND (LOWER(COALESCE(o.tumabodaStatus, '')) IN ('failed', 'returned', 'cancelled', 'canceled')
                   OR (o.tumabodaDeliveryId IS NULL AND o.tumabodaBookingFailure IS NOT NULL AND o.tumabodaNextRetryAt IS NULL))
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    long countNeedingAttention();

    /** For MockDataSeederService.status()'s order count — how many of the synthetic
     *  review/sale-event FK orders currently exist, regardless of enabled/disabled state. */
    long countByIsMockDataTrue();

    /** A signed-in customer's own orders, newest first. Lightweight projection — no line items are loaded. */
    @Query("""
            SELECT NEW com.nbh.backend.customer.dto.CustomerDtos$MyOrderDto(
                o.orderNo, o.status, (SELECT COUNT(li) FROM CustomerOrderItem li WHERE li.order = o AND li.parentItem IS NULL), o.grandTotal, o.createdAt)
            FROM CustomerOrder o
            WHERE o.customerAccount.id = :accountId
            ORDER BY o.createdAt DESC
            """)
    List<com.nbh.backend.customer.dto.CustomerDtos.MyOrderDto> findMine(@Param("accountId") UUID accountId);

    /**
     * Adopts earlier guest orders placed with this email. Only ever called once the
     * account's email is verified — see CustomerAuthService.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerOrder o SET o.customerAccount = :account
            WHERE LOWER(o.guestEmail) = LOWER(:email) AND o.customerAccount IS NULL
            """)
    int attachGuestOrders(@Param("account") com.nbh.backend.customer.entity.CustomerAccount account,
                          @Param("email") String email);

    /**
     * Revenue = grand total of orders whose payment actually succeeded
     * (PAID or later) — a PENDING_PAYMENT/CANCELLED/PAYMENT_FAILED/EXPIRED
     * order must never count, same "confirmed, not merely placed" principle
     * {@code ProductAnalyticsListener} uses for per-product sale events.
     * {@code since} is REQUIRED here (never null) — callers pass {@code
     * Instant.EPOCH} for "all time". Found live: unlike the {@code q}/LIKE
     * case above, a null {@code Instant} parameter used in a plain {@code
     * >=} comparison fails PostgreSQL's parameter type inference outright
     * ("could not determine data type of parameter"), and wrapping it in an
     * explicit {@code CAST(:since AS timestamp)} doesn't fix it either — the
     * driver had already bound the null as {@code bytea} before the cast
     * runs, so casting bytea to timestamp is itself a Postgres error. Rather
     * than fight the driver further, the null case is pushed to the caller
     * (a real, always-bindable {@code Instant} value) instead of the query.
     *
     * <p>Excludes the 28 {@code MOCK-}-prefixed demo orders unless demo data is still switched on
     * (same live signal as {@link #search} above — any mock product currently published) — found
     * live: turning demo data off unpublished the mock products but these two queries kept summing
     * and counting their orders anyway, so the dashboard's revenue/order figures never actually
     * went back to zero like the rest of the demo experience did.
     */
    @Query("""
            SELECT COALESCE(SUM(o.grandTotal), 0) FROM CustomerOrder o
            WHERE o.status IN :statuses AND o.createdAt >= :since
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    BigDecimal totalRevenue(@Param("statuses") Collection<OrderStatus> statuses, @Param("since") Instant since);

    @Query("""
            SELECT o.status AS status, COUNT(o) AS count FROM CustomerOrder o
            WHERE o.createdAt >= :since
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            GROUP BY o.status
            """)
    List<StatusCount> countByStatus(@Param("since") Instant since);

    interface StatusCount {
        OrderStatus getStatus();
        long getCount();
    }

    /**
     * TumaBoda settlement ledger (V48): every costed delivery not yet swept into a settlement —
     * {@code tumabodaCost} is only ever set once a booking actually happened (see
     * {@code TumaBodaBookingService}), so this needs no further status filtering. Oldest first,
     * matching how a bank reconciliation is normally worked.
     */
    @Query("""
            SELECT NEW com.nbh.backend.tumaboda.TumaBodaSettlementDtos$OutstandingLine(
                o.id, o.orderNo, o.customerName, o.tumabodaCost, o.createdAt)
            FROM CustomerOrder o
            WHERE o.tumabodaCost IS NOT NULL AND o.tumabodaSettlementId IS NULL
            ORDER BY o.createdAt ASC
            """)
    List<com.nbh.backend.tumaboda.TumaBodaSettlementDtos.OutstandingLine> findOutstandingTumabodaDeliveries();

    @Query("SELECT COALESCE(SUM(o.tumabodaCost), 0) FROM CustomerOrder o WHERE o.tumabodaCost IS NOT NULL AND o.tumabodaSettlementId IS NULL")
    BigDecimal totalOutstandingTumabodaCost();

    /** Sweeps every currently-outstanding costed delivery into one settlement — see {@code TumaBodaSettlementService.record}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CustomerOrder o SET o.tumabodaSettlementId = :settlementId WHERE o.tumabodaCost IS NOT NULL AND o.tumabodaSettlementId IS NULL")
    int sweepOutstandingTumabodaDeliveries(@Param("settlementId") UUID settlementId);

    long countByTumabodaSettlementId(UUID settlementId);

    /** One row per Nairobi calendar day that had orders — the overview's trend lines. Same demo-or-real rule as every other view. */
    interface DailyOrders {
        java.sql.Date getDay();
        long getOrders();
        BigDecimal getRevenue();
    }

    @Query(value = """
            SELECT CAST((o.created_at AT TIME ZONE 'Africa/Nairobi') AS date) AS day,
                   COUNT(*) AS orders,
                   COALESCE(SUM(CASE WHEN o.status IN ('PAID','PICKING','READY','DISPATCHED','DELIVERED')
                                     THEN o.grand_total ELSE 0 END), 0) AS revenue
            FROM customer_order o
            WHERE o.created_at >= :since
              AND ((o.is_mock_data AND EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published))
                   OR (NOT o.is_mock_data AND NOT EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published)))
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<DailyOrders> dailyOrders(@Param("since") Instant since);

    /**
     * Staff customer list: one row per phone number, newest activity first. Same demo-data gating as the
     * other admin views, so turning demo data off hides its synthetic customers too.
     */
    @Query(value = """
            SELECT NEW com.nbh.backend.customer.dto.AdminCustomerDto(
                o.guestPhone, MAX(o.customerName), MAX(o.guestEmail), COUNT(o),
                COALESCE(SUM(CASE WHEN o.status IN (
                        com.nbh.backend.order.entity.OrderStatus.PAID, com.nbh.backend.order.entity.OrderStatus.PICKING,
                        com.nbh.backend.order.entity.OrderStatus.READY, com.nbh.backend.order.entity.OrderStatus.DISPATCHED,
                        com.nbh.backend.order.entity.OrderStatus.DELIVERED)
                    THEN o.grandTotal ELSE 0 END), 0),
                MAX(o.createdAt), CASE WHEN COUNT(o.customerAccount) > 0 THEN true ELSE false END)
            FROM CustomerOrder o
            WHERE ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:q IS NULL
                   OR LOWER(COALESCE(o.customerName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR o.guestPhone LIKE CONCAT('%', CAST(:phoneQ AS string), '%'))
            GROUP BY o.guestPhone
            ORDER BY MAX(o.createdAt) DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT o.guestPhone) FROM CustomerOrder o
            WHERE ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:q IS NULL
                   OR LOWER(COALESCE(o.customerName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR o.guestPhone LIKE CONCAT('%', CAST(:phoneQ AS string), '%'))
            """)
    Page<com.nbh.backend.customer.dto.AdminCustomerDto> searchCustomers(@Param("q") String q,
                                                                          @Param("phoneQ") String phoneQ,
                                                                          Pageable pageable);

    /**
     * The orders placed with this phone number (stored in its 254… form), newest first, among the ones the staff screens
     * are showing: the real orders, or the demo ones while the demo catalogue is on. The first is what an order entered for
     * the same customer starts from, and the total is how many times they have ordered.
     */
    @Query("""
            SELECT o FROM CustomerOrder o
            WHERE o.guestPhone = :phone
              AND ((o.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (o.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            ORDER BY o.createdAt DESC
            """)
    Page<CustomerOrder> newestForPhone(@Param("phone") String phone, Pageable pageable);
}
