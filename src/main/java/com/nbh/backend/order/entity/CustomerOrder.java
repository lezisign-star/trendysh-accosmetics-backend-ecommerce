package com.nbh.backend.order.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Guest checkout is still supported alongside signed-in accounts (§4.7).
 * Branches were removed 2026-09-24 (founder decision — see HANDOFF.md); there
 * is one shop, so an order no longer names a fulfilment branch.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_order")
public class CustomerOrder extends BaseEntity {

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    /** Null for guest orders. Set when placed while signed in, or when an account's verified email adopts earlier guest orders. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_account_id")
    private com.nbh.backend.customer.entity.CustomerAccount customerAccount;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "guest_email", nullable = false, length = 255)
    private String guestEmail;

    @Column(name = "guest_phone", nullable = false, length = 20)
    private String guestPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_type", nullable = false, length = 20)
    private FulfilmentType fulfilmentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "vat_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatTotal;

    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryFee = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    /**
     * BODA, MATATU or COURIER for DELIVERY_COURIER orders — the customer's preferred manually-arranged
     * transport, all paid directly to the transporter and confirmed by phone (V33). Null for pickup and
     * for orders that pre-date V14. Historical VEHICLE values were migrated to MATATU by V33.
     */
    @Column(name = "delivery_method", length = 20)
    private String deliveryMethod;

    @Column(name = "placed_at")
    private Instant placedAt;

    /** Who was actually assigned to carry a DELIVERY_COURIER order, and what they and the customer agreed on the phone (V35). Null until {@code OrderFulfilmentService.assignTransporter} runs; always null for PICKUP. */
    @Column(name = "transporter_name", length = 200)
    private String transporterName;

    @Column(name = "transporter_phone", length = 20)
    private String transporterPhone;

    @Column(name = "agreed_delivery_price", precision = 14, scale = 2)
    private BigDecimal agreedDeliveryPrice;

    // ---- TumaBoda API delivery (V38). All null for orders arranged by phone. ----

    /** Where a TumaBoda rider drops off, from the address the customer picked at checkout. Present means "book through the API". */
    @Column(name = "dropoff_lat", precision = 10, scale = 7)
    private BigDecimal dropoffLat;

    @Column(name = "dropoff_lng", precision = 10, scale = 7)
    private BigDecimal dropoffLng;

    @Column(name = "tumaboda_delivery_id", length = 100)
    private String tumabodaDeliveryId;

    @Column(name = "tumaboda_delivery_number", length = 100)
    private String tumabodaDeliveryNumber;

    @Column(name = "tumaboda_tracking_code", length = 100)
    private String tumabodaTrackingCode;

    @Column(name = "tumaboda_tracking_url", length = 500)
    private String tumabodaTrackingUrl;

    /** TumaBoda's own status string, stored as received (in_transit, delivered, failed, ...). */
    @Column(name = "tumaboda_status", length = 40)
    private String tumabodaStatus;

    /** What TumaBoda charged the shop for this delivery; the customer paid {@code deliveryFee}, the quote at checkout. */
    @Column(name = "tumaboda_cost", precision = 14, scale = 2)
    private BigDecimal tumabodaCost;

    @Column(name = "tumaboda_rider_id", length = 100)
    private String tumabodaRiderId;

    @Column(name = "tumaboda_last_webhook_at")
    private Instant tumabodaLastWebhookAt;

    @Column(name = "tumaboda_booking_attempts", nullable = false)
    private int tumabodaBookingAttempts = 0;

    @Column(name = "tumaboda_next_retry_at")
    private Instant tumabodaNextRetryAt;

    @Column(name = "tumaboda_booking_failure", length = 500)
    private String tumabodaBookingFailure;

    /**
     * How many times staff booked a fresh rider after TumaBoda reported the delivery failed, returned or
     * cancelled. Each restart needs a new TumaBoda reference ("SO-...-R1"): reusing the order number would
     * just hand back the dead delivery.
     */
    @Column(name = "tumaboda_restart_count", nullable = false)
    private int tumabodaRestartCount = 0;

    /** Staff moved this order back to a phone-arranged transporter after a booking problem. */
    @Column(name = "tumaboda_rerouted", nullable = false)
    private boolean tumabodaRerouted = false;

    /** True when this order is to be (or has been) delivered by a TumaBoda rider booked through the API. */
    public boolean isTumabodaApiDelivery() {
        return dropoffLat != null && dropoffLng != null && !tumabodaRerouted;
    }

    /** TumaBoda statuses meaning the delivery is over and will not be completed. */
    public static boolean isDeadTumabodaStatus(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "failed", "returned", "cancelled", "canceled" -> true;
            default -> false;
        };
    }

    /** A rider is booked and TumaBoda has not reported the delivery failed, returned or cancelled. */
    public boolean hasLiveTumabodaDelivery() {
        return tumabodaDeliveryId != null && !isDeadTumabodaStatus(tumabodaStatus);
    }

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CustomerOrderItem> items = new ArrayList<>();
}
