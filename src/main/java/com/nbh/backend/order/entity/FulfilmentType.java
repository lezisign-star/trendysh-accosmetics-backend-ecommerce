package com.nbh.backend.order.entity;

/**
 * TumaBoda is no longer used (frontend rebrand decision, 2026-09-15) — the
 * team arranges any rider/vehicle transport manually and the customer pays
 * that courier directly, so there is no separate "TumaBoda" fulfilment path
 * distinct from a generic courier delivery. See
 * {@code V12__align_delivery_model_with_business_decision.sql}.
 */
public enum FulfilmentType {
    DELIVERY_COURIER,
    BRANCH_PICKUP
}
