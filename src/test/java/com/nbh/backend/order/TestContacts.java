package com.nbh.backend.order;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Checkout caps how many unpaid orders one phone or email can have open at once, and every
 * integration test shares one database, so tests that place orders must not share a contact —
 * a handful of earlier tests' unpaid orders would otherwise push a later one over the cap.
 */
final class TestContacts {

    private TestContacts() {}

    static String phone() {
        return "07" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    static String email() {
        return "t-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
