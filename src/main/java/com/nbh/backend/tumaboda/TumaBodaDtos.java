package com.nbh.backend.tumaboda;

import java.math.BigDecimal;

/** What crosses the boundary to and from TumaBoda's partner API, already converted to KES and Java types. */
public final class TumaBodaDtos {

    private TumaBodaDtos() {}

    /** Package sizes the API knows; {@code wire} is what goes in the request body. */
    public enum PackageSize {
        SMALL("small"), MEDIUM("medium"), LARGE("large"), EXTRA_LARGE("extra_large");

        private final String wire;

        PackageSize(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** The package as far as this shop can tell: a size, and whether it needs a van rather than a motorbike. */
    public record PackageSpec(PackageSize size, boolean van) {}

    /** {@code totalKes} is what TumaBoda charges for the delivery; the customer is charged this at checkout. */
    public record Quote(BigDecimal totalKes, BigDecimal serviceFeeKes, BigDecimal distanceKm, Integer durationMinutes) {}

    public record Recipient(String name, String phone, String location, BigDecimal latitude, BigDecimal longitude) {}

    /** {@code reference} is the idempotency key: the same reference always maps to the same TumaBoda delivery. */
    public record DeliveryRequest(Recipient recipient, String description, PackageSpec packageSpec, String reference) {}

    /** A delivery as TumaBoda reports it. Any field may be null: the API fills them in as the delivery progresses. */
    public record Delivery(String id, String deliveryNumber, String status, String trackingCode, String trackingUrl,
                           String riderId, BigDecimal totalKes) {}

    /** One address suggestion; {@code placeId} is resolved to coordinates with a second call. */
    public record PlaceSuggestion(String placeId, String description) {}

    /** A chosen place resolved to the point a rider will be sent to. */
    public record PlaceDetails(String placeId, BigDecimal latitude, BigDecimal longitude, String address, String name) {}
}
