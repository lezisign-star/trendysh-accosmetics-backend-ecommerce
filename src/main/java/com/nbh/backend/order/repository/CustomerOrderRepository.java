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

    /** Ids only — feeds {@code OrderExpiryService}, which then locks and re-checks each order itself. */
    @Query("select o.id from CustomerOrder o where o.status = :status")
    List<UUID> findIdsByStatus(@Param("status") OrderStatus status);

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
     * Staff order-browse list — one query, no N+1 (F12 pattern): {@code
     * SIZE(o.items)} is a correlated-count subquery, not a fetch of the
     * items collection itself. {@code status}/{@code q} are optional; the
     * {@code CAST(:q AS string)} is required for the same reason
     * {@code ProductRepository.searchPublished} needed it — a null bind
     * parameter reused inside LOWER/LIKE otherwise defaults to bytea on
     * PostgreSQL and crashes every unfiltered call, not just filtered ones
     * (found live in session 10; same fix applied here pre-emptively).
     */
    @Query("""
            SELECT NEW com.nbh.backend.order.dto.OrderSummaryDto(
                o.id, o.orderNo, o.status, o.customerName, o.guestEmail, o.guestPhone,
                SIZE(o.items), o.grandTotal, o.createdAt)
            FROM CustomerOrder o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:q IS NULL
                   OR LOWER(o.orderNo) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.customerName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(o.guestEmail) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR o.guestPhone LIKE CONCAT('%', CAST(:phoneQ AS string), '%'))
            ORDER BY o.createdAt DESC
            """)
    Page<OrderSummaryDto> search(@Param("status") OrderStatus status, @Param("q") String q,
                                  @Param("phoneQ") String phoneQ, Pageable pageable);

    /** A signed-in customer's own orders, newest first. Lightweight projection — no line items are loaded. */
    @Query("""
            SELECT NEW com.nbh.backend.customer.dto.CustomerDtos$MyOrderDto(
                o.orderNo, o.status, SIZE(o.items), o.grandTotal, o.createdAt)
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
     */
    @Query("""
            SELECT COALESCE(SUM(o.grandTotal), 0) FROM CustomerOrder o
            WHERE o.status IN :statuses AND o.createdAt >= :since
            """)
    BigDecimal totalRevenue(@Param("statuses") Collection<OrderStatus> statuses, @Param("since") Instant since);

    @Query("""
            SELECT o.status AS status, COUNT(o) AS count FROM CustomerOrder o
            WHERE o.createdAt >= :since
            GROUP BY o.status
            """)
    List<StatusCount> countByStatus(@Param("since") Instant since);

    interface StatusCount {
        OrderStatus getStatus();
        long getCount();
    }
}
