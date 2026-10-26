package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.service.OrderUrgency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The list-view projection for staff browsing orders — deliberately lighter
 * than {@link OrderDto}, which eagerly maps every line item. Matches the
 * F12 pattern ({@code ProductService.listProducts}): a summary for the list,
 * full detail only on a single-order fetch. {@code status} is typed as the
 * real enum (not a pre-stringified value) so it can be used directly in a
 * JPQL constructor-expression query without a type mismatch — Jackson still
 * serialises it as a plain string in the JSON response either way.
 */
public record OrderSummaryDto(
        UUID id,
        String orderNo,
        OrderStatus status,
        String customerName,
        String guestEmail,
        String guestPhone,
        long itemCount,
        BigDecimal grandTotal,
        Instant createdAt,
        /**
         * Same signal as {@code AdminOrderDetailDto.Tumaboda.needsAttention} — a booking is stuck
         * or TumaBoda reports the delivery failed — now surfaced at list level too (Phase 3,
         * 2026-10-02) instead of only once staff open the order.
         */
        boolean needsAttention,
        String tumabodaBookingFailure,
        /** Red-warning flag for the fulfilment queue — see {@link OrderUrgency}; filled in after the query. */
        boolean urgent,
        /** "From yesterday" or "Placed overnight" when {@link #urgent}, else null. */
        String urgentReason,
        /** PICKUP or DELIVERY_COURIER, so the list can say how each order leaves the shop without opening it. */
        FulfilmentType fulfilmentType,
        /**
         * For a pickup, the shop the customer chose at checkout ("Simara Mall — Shop GS 07"); null for a delivery
         * (whose address stays in the detail view) and for pickups placed before the shop picker existed.
         */
        String pickupPlace,
        /**
         * For an order still waiting on money: NONE (no M-PESA code entered yet), PENDING (one is waiting for a check)
         * or REJECTED — see {@code PaymentCodeStatus}. Null for every other status. Filled in after the query.
         */
        String paymentCode
) {
    /** The shape the JPQL constructor expression selects; urgency and the code status are worked out afterwards. */
    public OrderSummaryDto(UUID id, String orderNo, OrderStatus status, String customerName, String guestEmail,
                           String guestPhone, long itemCount, BigDecimal grandTotal, Instant createdAt,
                           boolean needsAttention, String tumabodaBookingFailure,
                           FulfilmentType fulfilmentType, String deliveryAddress) {
        this(id, orderNo, status, customerName, guestEmail, guestPhone, itemCount, grandTotal, createdAt,
                needsAttention, tumabodaBookingFailure, false, null, fulfilmentType,
                fulfilmentType == FulfilmentType.PICKUP && deliveryAddress != null && !deliveryAddress.isBlank()
                        ? deliveryAddress.trim() : null,
                null);
    }

    public OrderSummaryDto withUrgency(Instant now) {
        String reason = OrderUrgency.reason(createdAt, now);
        return new OrderSummaryDto(id, orderNo, status, customerName, guestEmail, guestPhone, itemCount, grandTotal,
                createdAt, needsAttention, tumabodaBookingFailure, reason != null, reason, fulfilmentType,
                pickupPlace, paymentCode);
    }

    public OrderSummaryDto withPaymentCode(String code) {
        return new OrderSummaryDto(id, orderNo, status, customerName, guestEmail, guestPhone, itemCount, grandTotal,
                createdAt, needsAttention, tumabodaBookingFailure, urgent, urgentReason, fulfilmentType,
                pickupPlace, code);
    }
}
