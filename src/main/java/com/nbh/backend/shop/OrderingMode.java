package com.nbh.backend.shop;

/**
 * How the shop takes orders. {@code FULL}: the website takes orders and payments as usual. {@code WHATSAPP}: the website
 * shows the shop and its prices and the shopper sends their basket on WhatsApp; nothing is saved as an order. Only the
 * storefront on the .com domain honours it (the .site test copy always takes orders), so the order and payment endpoints
 * themselves work in either mode.
 */
public enum OrderingMode {
    FULL, WHATSAPP
}
