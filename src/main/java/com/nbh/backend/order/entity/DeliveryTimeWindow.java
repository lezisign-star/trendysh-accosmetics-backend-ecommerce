package com.nbh.backend.order.entity;

/**
 * When the customer would like a delivery to arrive (V55). Only a wish: dispatch is still confirmed with them by phone.
 * Null on an order means they said nothing.
 */
public enum DeliveryTimeWindow {
    MORNING,
    AFTERNOON,
    EVENING,
    ANY_TIME
}
