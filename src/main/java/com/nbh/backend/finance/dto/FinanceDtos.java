package com.nbh.backend.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What the website has made, and the path each payment took (owner, 2026-10-23). See {@code FinanceService}. */
public final class FinanceDtos {

    private FinanceDtos() {}

    /**
     * Money received in [from, to): payments that succeeded, counted on the day the order was marked paid. {@code received}
     * is what came in; {@code sales} splits the same orders into what was for products and what was the delivery fee the
     * shop collects for a rider booked through the website; {@code split} says who placed the orders (the customer on the
     * website, or staff entering a call or a WhatsApp order); {@code notCompleted} are orders of the period that ended with
     * no money at all; {@code waiting} is what is owed right now, whatever the period.
     */
    public record Summary(Instant from, Instant to, Received received, Sales sales, Split split, Waiting waiting,
                          List<Lost> notCompleted, PriceChanges priceChanges, List<Day> daily) {}

    /** {@code ofWhichCancelled}: money taken for orders that were cancelled afterwards (a refund may be owed). */
    public record Received(BigDecimal total, long orders, BigDecimal ofWhichCancelled, List<ByMethod> byMethod) {}

    public record ByMethod(String method, long payments, BigDecimal amount) {}

    public record Sales(BigDecimal products, BigDecimal delivery) {}

    public record Split(Part customerPlaced, Part staffEntered) {}

    public record Part(long orders, BigDecimal received) {}

    public record Waiting(long orders, BigDecimal amount) {}

    /** Orders that ended with no money: {@code status} is CANCELLED, EXPIRED or PAYMENT_FAILED. */
    public record Lost(String status, long orders, BigDecimal amount) {}

    /** What staff did to prices while entering orders: given away, raised, and how many still need an answer. */
    public record PriceChanges(long changes, BigDecimal givenAway, BigDecimal raised, long waiting, long disputed) {}

    public record Day(LocalDate day, long orders, BigDecimal received, BigDecimal staffEntered) {}

    /**
     * One payment received and how it got to the shop. {@code confirmedHow} says what made it count (the M-PESA
     * confirmation, staff confirming it, the customer's code approved, a bank credit matched, money taken with the order)
     * and {@code confirmedBy} who, when known. {@code placedBy} is the member of staff who entered the order; null for an
     * order the customer placed on the website.
     */
    public record LedgerEntry(UUID paymentId, UUID orderId, String orderNo, Instant paidAt, String customerName,
                              String customerPhone, String method, BigDecimal amount, String reference,
                              BigDecimal products, BigDecimal delivery, String placedBy, String confirmedHow,
                              String confirmedBy, String customerCode, String bankTransaction, long priceChanges,
                              BigDecimal amountOff) {}

    /**
     * One change of a product option's normal selling price, kept automatically (V73). {@code oldPrice} is null when the
     * option had no price, {@code newPrice} when the price was taken away; {@code changedBy} is the staff member, "system"
     * for a background job, or null when the record does not say.
     */
    public record PriceHistoryEntry(UUID variantId, String productName, String option, String sku, BigDecimal oldPrice,
                                    BigDecimal newPrice, String changedBy, Instant changedAt) {}

    /** The life of one order's money, oldest first. {@code stillOwed} is what is left to be paid (0 when settled). */
    public record Trail(UUID orderId, String orderNo, String status, String customerName, String customerPhone,
                        String placedBy, BigDecimal subtotal, BigDecimal deliveryFee, BigDecimal grandTotal,
                        BigDecimal received, BigDecimal stillOwed, List<Step> steps) {}

    /** One thing that happened to the money: {@code kind} groups them for icons, {@code title} says it in plain words. */
    public record Step(Instant at, String kind, String title, String detail) {}
}
