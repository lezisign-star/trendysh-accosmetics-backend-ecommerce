package com.nbh.backend.staff.entity;

/**
 * Flat capability set, adapted from Moments Packaging's Permission enum
 * (user/entity/Permission.java) plus the stock/POS/branch permissions
 * NBH needs that Moments doesn't have (see architecture doc section 4.8).
 */
public enum Permission {

    // Orders (online)
    ORDER_VIEW,
    ORDER_MANAGE_ALL,

    // Catalogue
    PRODUCT_VIEW,
    PRODUCT_MANAGE,

    // Stock — new for NBH, Moments has no equivalent
    STOCK_VIEW,
    STOCK_RECEIVE,
    STOCK_ADJUST,
    STOCK_COUNT,
    STOCK_TRANSFER_INITIATE,
    STOCK_TRANSFER_APPROVE,

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

    // Branch
    BRANCH_MANAGE,

    // Staff / role management
    USER_VIEW,
    USER_CREATE,
    USER_MANAGE_ROLES,

    // Analytics & settings
    ANALYTICS_VIEW,
    SETTINGS_MANAGE,

    // Audit log
    AUDIT_VIEW,

    // Customers
    CUSTOMER_VIEW
}
