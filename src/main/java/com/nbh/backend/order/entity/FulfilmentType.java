package com.nbh.backend.order.entity;

/**
 * TumaBoda is no longer used (frontend rebrand decision, 2026-09-15) — the
 * team arranges any rider/vehicle transport manually and the customer pays
 * that courier directly, so there is no separate "TumaBoda" fulfilment path
 * distinct from a generic courier delivery. See
 * {@code V12__align_delivery_model_with_business_decision.sql}.
 *
 * <p>{@code BRANCH_PICKUP} was renamed {@code PICKUP} 2026-09-24 when branches
 * were removed (founder decision — see HANDOFF.md): there is one shop, so
 * "branch" pickup is just pickup. {@code V26__single_stock_account.sql}
 * migrates the stored value on existing orders.
 */
public enum FulfilmentType {
    DELIVERY_COURIER,
    PICKUP
}
