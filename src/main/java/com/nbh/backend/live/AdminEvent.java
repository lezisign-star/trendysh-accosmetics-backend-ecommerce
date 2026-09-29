package com.nbh.backend.live;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * A hint pushed to staff dashboards over the live stream: what happened and, where there is one, which
 * order and how much. It carries no customer details and is only ever a prompt to look again: every screen
 * re-reads the real data from the API, so a missed or duplicated hint can never leave a dashboard wrong for
 * longer than its next refresh.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminEvent(Type type, String orderNo, BigDecimal amount) {

    public enum Type {
        /** A customer placed an order (now waiting for its Paybill payment). */
        ORDER_PLACED,
        /** An order moved on: picking, ready, dispatched, delivered or cancelled. */
        ORDER_UPDATED,
        /** A payment was confirmed, by staff, a bank credit or a callback. */
        PAYMENT_CONFIRMED,
        PAYMENT_FAILED,
        /** A customer says they've paid; staff have to check it against the statement. */
        PAYMENT_CLAIMED,
        /** Money arrived at the bank account (matched to an order or not). */
        BANK_CREDIT,
        /** TumaBoda booked a rider or reported progress on a delivery. */
        DELIVERY_UPDATED
    }

    public static AdminEvent of(Type type, String orderNo) {
        return new AdminEvent(type, orderNo, null);
    }
}
