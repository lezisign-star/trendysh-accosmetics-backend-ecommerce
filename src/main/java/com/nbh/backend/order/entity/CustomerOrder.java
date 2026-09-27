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

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CustomerOrderItem> items = new ArrayList<>();
}
