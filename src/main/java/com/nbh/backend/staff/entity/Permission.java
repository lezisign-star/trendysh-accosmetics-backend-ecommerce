package com.nbh.backend.staff.entity;

/**
 * Flat capability set, adapted from Moments Packaging's Permission enum
 * (user/entity/Permission.java) plus the stock/POS permissions NBH needs
 * that Moments doesn't have (see architecture doc section 4.8).
 *
 * <p>BRANCH_MANAGE, STOCK_TRANSFER_INITIATE and STOCK_TRANSFER_APPROVE were
 * removed 2026-09-24 when branches were eliminated (founder decision — see
 * HANDOFF.md): there is one shop, nothing to transfer between, and nothing
 * left to manage per-branch.
 */
public enum Permission {

    // Orders (online)
    ORDER_VIEW,
    ORDER_MANAGE_ALL,
    /**
     * Enter an order for a customer who phoned, messaged on WhatsApp or stood at the counter (owner, 2026-10-23: every
     * role but the read-only one). Narrower than {@link #ORDER_MANAGE_ALL}: it places the order and nothing else.
     */
    ORDER_ENTER,
    /**
     * Change a line's price on an order being entered without anyone else's OK, and be named as the person who OKs
     * someone else's change (owner and managers). Everyone else records who gave a verbal OK and what was said.
     */
    ORDER_PRICE_APPROVE,

    // Catalogue
    PRODUCT_VIEW,
    PRODUCT_MANAGE,

    // Stock — new for NBH, Moments has no equivalent
    STOCK_VIEW,
    STOCK_RECEIVE,
    STOCK_ADJUST,
    STOCK_COUNT,

    // POS — new for NBH, reserved ahead of the Phase-4 POS UI
    POS_SELL,
    POS_OPEN_SESSION,
    POS_CLOSE_SESSION,
    POS_DISCOUNT,
    POS_VOID,

    // Payments
    PAYMENT_VIEW,
    PAYMENT_MANAGE,
    PAYMENT_REFUND,
    /**
     * Sales &amp; money: what the website made, every payment with its trail (and the spreadsheet of them, with customers'
     * phones), the price changes staff made. Not for the read-only Viewer (owner, 2026-10-23).
     */
    FINANCE_VIEW,

    // Staff / role management
    USER_VIEW,
    USER_CREATE,
    USER_MANAGE_ROLES,

    // Analytics & settings
    ANALYTICS_VIEW,
    SETTINGS_MANAGE,
    /** Edit the Paybill/bank/WhatsApp details customers pay to — held by Managers without the rest of Settings. */
    PAYMENT_SETTINGS_MANAGE,

    // Audit log
    AUDIT_VIEW,

    // Customers
    CUSTOMER_VIEW
}
