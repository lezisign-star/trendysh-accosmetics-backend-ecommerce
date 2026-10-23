package com.nbh.backend.finance.repository;

import com.nbh.backend.order.entity.CustomerOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The money the website has taken, worked out from the payments themselves (owner, 2026-10-23: how much has the website
 * made over a period, and where did it come from). Money counts when a payment SUCCEEDED, on the day the order was marked
 * paid ({@code placed_at}, set by {@code PaymentService.confirmSuccess}), whichever way it was confirmed: the M-PESA
 * confirmation, a code approved by staff, a bank credit, or money taken by staff with the order. Native SQL, because it
 * aggregates across payments, orders, claims and bank credits; every query shows only the orders the staff screens
 * are showing (the real ones, or the demo ones while the demo catalogue is on), like the rest of the dashboard.
 */
public interface FinanceRepository extends Repository<CustomerOrder, UUID> {

    /** The orders the staff screens are showing: the real ones, or the demo ones while the demo catalogue is on. */
    String VISIBLE = """
            ((o.is_mock_data AND EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published))
             OR (NOT o.is_mock_data AND NOT EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published)))
            """;

    /** One row per order paid in the period, with what was received for it. */
    String PAID = """
            WITH paid AS (
                SELECT o.id AS order_id, o.placed_by, o.placed_at, o.subtotal, o.delivery_fee, o.status AS order_status,
                       SUM(p.amount) AS paid
                FROM customer_order o
                JOIN payment_record p ON p.order_id = o.id AND p.status = 'SUCCESS'
                WHERE o.placed_at >= :from AND o.placed_at < :to AND
            """ + VISIBLE + """
                GROUP BY o.id
            )
            """;

    interface Totals {
        long getOrders();

        BigDecimal getReceived();

        BigDecimal getProducts();

        BigDecimal getDelivery();

        BigDecimal getCancelledAfterPaid();

        long getStaffOrders();

        BigDecimal getStaffReceived();
    }

    @Query(value = PAID + """
            SELECT COUNT(*) AS orders,
                   COALESCE(SUM(paid), 0) AS received,
                   COALESCE(SUM(subtotal), 0) AS products,
                   COALESCE(SUM(delivery_fee), 0) AS delivery,
                   COALESCE(SUM(CASE WHEN order_status = 'CANCELLED' THEN paid ELSE 0 END), 0) AS "cancelledAfterPaid",
                   COUNT(*) FILTER (WHERE placed_by IS NOT NULL) AS "staffOrders",
                   COALESCE(SUM(CASE WHEN placed_by IS NOT NULL THEN paid ELSE 0 END), 0) AS "staffReceived"
            FROM paid
            """, nativeQuery = true)
    Totals totals(@Param("from") Instant from, @Param("to") Instant to);

    interface MethodTotal {
        String getMethod();

        long getPayments();

        BigDecimal getAmount();
    }

    @Query(value = PAID + """
            SELECT p.method AS method, COUNT(*) AS payments, COALESCE(SUM(p.amount), 0) AS amount
            FROM payment_record p
            JOIN paid d ON d.order_id = p.order_id
            WHERE p.status = 'SUCCESS'
            GROUP BY p.method
            ORDER BY SUM(p.amount) DESC, p.method
            """, nativeQuery = true)
    List<MethodTotal> byMethod(@Param("from") Instant from, @Param("to") Instant to);

    interface DayTotal {
        Date getDay();

        long getOrders();

        BigDecimal getReceived();

        BigDecimal getStaffReceived();
    }

    /** One row per Nairobi calendar day that had money in it. */
    @Query(value = PAID + """
            SELECT CAST((placed_at AT TIME ZONE 'Africa/Nairobi') AS date) AS day,
                   COUNT(*) AS orders,
                   COALESCE(SUM(paid), 0) AS received,
                   COALESCE(SUM(CASE WHEN placed_by IS NOT NULL THEN paid ELSE 0 END), 0) AS "staffReceived"
            FROM paid
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<DayTotal> byDay(@Param("from") Instant from, @Param("to") Instant to);

    interface Waiting {
        long getOrders();

        BigDecimal getAmount();
    }

    /** Orders placed and not yet paid, whatever the period: money the shop is waiting for right now. */
    @Query(value = """
            SELECT COUNT(*) AS orders, COALESCE(SUM(o.grand_total), 0) AS amount
            FROM customer_order o
            WHERE o.status = 'PENDING_PAYMENT' AND
            """ + VISIBLE, nativeQuery = true)
    Waiting waitingForPayment();

    interface LostTotal {
        String getStatus();

        long getOrders();

        BigDecimal getAmount();
    }

    /** Orders placed in the period that ended without any money (cancelled, expired, payment failed). */
    @Query(value = """
            SELECT o.status AS status, COUNT(*) AS orders, COALESCE(SUM(o.grand_total), 0) AS amount
            FROM customer_order o
            WHERE o.created_at >= :from AND o.created_at < :to
              AND o.status IN ('CANCELLED', 'EXPIRED', 'PAYMENT_FAILED')
              AND NOT EXISTS (SELECT 1 FROM payment_record p WHERE p.order_id = o.id AND p.status = 'SUCCESS')
              AND
            """ + VISIBLE + """
            GROUP BY o.status
            ORDER BY o.status
            """, nativeQuery = true)
    List<LostTotal> notCompleted(@Param("from") Instant from, @Param("to") Instant to);

    interface ChangeTotals {
        long getChanges();

        BigDecimal getGivenAway();

        BigDecimal getRaised();

        long getWaiting();

        long getDisputed();
    }

    /** What staff did to prices while entering orders in the period (V72). */
    @Query(value = """
            SELECT COUNT(*) AS changes,
                   COALESCE(SUM(CASE WHEN c.amount_off > 0 THEN c.amount_off ELSE 0 END), 0) AS "givenAway",
                   COALESCE(SUM(CASE WHEN c.amount_off < 0 THEN -c.amount_off ELSE 0 END), 0) AS raised,
                   COUNT(*) FILTER (WHERE c.ack_status = 'PENDING') AS waiting,
                   COUNT(*) FILTER (WHERE c.ack_status = 'DISPUTED') AS disputed
            FROM order_price_change c
            JOIN customer_order o ON o.id = c.order_id
            WHERE c.created_at >= :from AND c.created_at < :to AND
            """ + VISIBLE, nativeQuery = true)
    ChangeTotals priceChanges(@Param("from") Instant from, @Param("to") Instant to);

    /** One payment received, with the path it took (the code the customer sent, the bank credit it matched). */
    interface LedgerRow {
        UUID getPaymentId();

        UUID getOrderId();

        String getOrderNo();

        /** When the order was marked paid, in epoch milliseconds (a plain number: no guessing how the driver types a timestamp). */
        long getPaidAtMillis();

        String getCustomerName();

        String getCustomerPhone();

        String getMethod();

        BigDecimal getAmount();

        String getReference();

        BigDecimal getProducts();

        BigDecimal getDelivery();

        String getPlacedBy();

        /** The kind of note kept with the confirmation (staff_entered_order, manual_staff_confirmation, claim_approved, bank_credit), or null. */
        String getConfirmType();

        String getConfirmBy();

        /** True when the confirmation is Safaricom's own M-PESA callback. */
        boolean getMpesaCallback();

        /**
         * True when the payment had been marked failed and was confirmed after all (PaymentService keeps the earlier note
         * and the confirmation's own under "now"; the columns above read the "now" one).
         */
        boolean getRevived();

        String getClaimCode();

        String getClaimResolvedBy();

        String getBankTxnId();

        String getBankResolvedBy();

        long getPriceChanges();

        BigDecimal getAmountOff();
    }

    /** One change of a variant's selling price (V73). */
    interface PriceHistoryRow {
        UUID getVariantId();

        String getProductName();

        String getVariantName();

        String getOptionLabel();

        String getSku();

        BigDecimal getOldPrice();

        BigDecimal getNewPrice();

        String getChangedBy();

        long getChangedAtMillis();
    }

    String PRICE_HISTORY_FROM = """
            FROM variant_price_history h
            JOIN product_variant v ON v.id = h.variant_id
            JOIN product p ON p.id = v.product_id
            WHERE h.changed_at >= :from AND h.changed_at < :to
              AND (:q = '' OR LOWER(p.name) LIKE :like OR LOWER(v.sku) LIKE :like
                   OR LOWER(COALESCE(v.variant_name, '')) LIKE :like OR LOWER(COALESCE(v.option_label, '')) LIKE :like)
              AND ((p.is_mock_data AND EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published))
                   OR (NOT p.is_mock_data AND NOT EXISTS (SELECT 1 FROM product mp WHERE mp.is_mock_data AND mp.is_published)))
            """;

    /** Selling price changes in the period, newest first; {@code q} "" for all, else {@code like} is its lower-case "%text%". */
    @Query(value = """
            SELECT h.variant_id AS "variantId", p.name AS "productName", v.variant_name AS "variantName",
                   v.option_label AS "optionLabel", v.sku AS sku, h.old_price AS "oldPrice", h.new_price AS "newPrice",
                   h.changed_by AS "changedBy", CAST(EXTRACT(EPOCH FROM h.changed_at) * 1000 AS bigint) AS "changedAtMillis"
            """ + PRICE_HISTORY_FROM + """
            ORDER BY h.changed_at DESC, h.id
            """,
            countQuery = "SELECT COUNT(*) " + PRICE_HISTORY_FROM,
            nativeQuery = true)
    Page<PriceHistoryRow> priceHistory(@Param("from") Instant from, @Param("to") Instant to, @Param("q") String q,
                                       @Param("like") String like, Pageable pageable);

    String LEDGER_FROM = """
            FROM payment_record p
            JOIN customer_order o ON o.id = p.order_id
            LEFT JOIN payment_claim cl ON cl.id = CAST(NULLIF((COALESCE(NULLIF(p.raw_response -> 'now', 'null'::jsonb), p.raw_response)) ->> 'claimId', '') AS uuid)
            LEFT JOIN bank_credit bc ON bc.id = CAST(NULLIF((COALESCE(NULLIF(p.raw_response -> 'now', 'null'::jsonb), p.raw_response)) ->> 'creditId', '') AS uuid)
            WHERE p.status = 'SUCCESS' AND o.placed_at >= :from AND o.placed_at < :to
              AND (:method = '' OR p.method = :method)
              AND (:source = 'ALL' OR (:source = 'STAFF' AND o.placed_by IS NOT NULL)
                                   OR (:source = 'CUSTOMER' AND o.placed_by IS NULL))
              AND (:q = '' OR LOWER(o.order_no) LIKE :like OR o.guest_phone LIKE :phoneLike
                   OR LOWER(COALESCE(p.provider_reference, '')) LIKE :like
                   OR LOWER(COALESCE(o.customer_name, '')) LIKE :like)
              AND
            """ + VISIBLE;

    /**
     * Payments received in the period, newest first. {@code method} is "" for any, {@code source} is ALL, STAFF (orders
     * staff entered) or CUSTOMER; {@code q} is "" for no search, else {@code like} is its lower-case "%text%" and
     * {@code phoneLike} its digits as "%digits%" (never null, which Postgres cannot type).
     */
    @Query(value = """
            SELECT p.id AS "paymentId", o.id AS "orderId", o.order_no AS "orderNo",
                   CAST(EXTRACT(EPOCH FROM o.placed_at) * 1000 AS bigint) AS "paidAtMillis",
                   o.customer_name AS "customerName", o.guest_phone AS "customerPhone", p.method AS method,
                   p.amount AS amount, p.provider_reference AS reference, o.subtotal AS products,
                   o.delivery_fee AS delivery, o.placed_by AS "placedBy",
                   (COALESCE(NULLIF(p.raw_response -> 'now', 'null'::jsonb), p.raw_response)) ->> 'type' AS "confirmType", (COALESCE(NULLIF(p.raw_response -> 'now', 'null'::jsonb), p.raw_response)) ->> 'by' AS "confirmBy",
                   COALESCE(p.raw_response ->> 'type' = 'revived_after_failure', false) AS "revived",
                   ((COALESCE(NULLIF(p.raw_response -> 'now', 'null'::jsonb), p.raw_response)) -> 'Body' IS NOT NULL) AS "mpesaCallback",
                   cl.transaction_code AS "claimCode", cl.resolved_by AS "claimResolvedBy",
                   bc.bank_txn_id AS "bankTxnId", bc.resolved_by AS "bankResolvedBy",
                   (SELECT COUNT(*) FROM order_price_change c WHERE c.order_id = o.id) AS "priceChanges",
                   (SELECT COALESCE(SUM(c.amount_off), 0) FROM order_price_change c WHERE c.order_id = o.id) AS "amountOff"
            """ + LEDGER_FROM + """
            ORDER BY o.placed_at DESC, p.id
            """,
            countQuery = "SELECT COUNT(*) " + LEDGER_FROM,
            nativeQuery = true)
    Page<LedgerRow> ledger(@Param("from") Instant from, @Param("to") Instant to, @Param("method") String method,
                           @Param("source") String source, @Param("q") String q, @Param("like") String like,
                           @Param("phoneLike") String phoneLike, Pageable pageable);
}
