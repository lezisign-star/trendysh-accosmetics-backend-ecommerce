package com.nbh.backend.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One customer as staff see them, grouped by the phone number orders were placed with (guest checkout is
 * the norm, so a phone is the one identity every order has). {@code spent} counts only orders whose payment
 * was confirmed — a placed-but-unpaid or cancelled order isn't revenue; {@code hasAccount} is true when any of
 * their orders was placed while signed in.
 */
public record AdminCustomerDto(
        String phone,
        String name,
        String email,
        long orders,
        BigDecimal spent,
        Instant lastOrderAt,
        boolean hasAccount
) {}
