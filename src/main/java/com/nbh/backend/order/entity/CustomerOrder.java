package com.nbh.backend.order.entity;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Guest checkout only, per architecture doc §4.7 and the current lack of a
 * customer account system. fulfilmentBranch is required from day one — for
 * now it is always the founder-confirmed default branch (session 5); smarter
 * assignment (nearest-to-address, whichever-has-stock) is deferred, not
 * decided against.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_order")
public class CustomerOrder extends BaseEntity {

    @Column(name = "order_no", nullable = false, unique = true, length = 40)
    private String orderNo;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "guest_email", nullable = false, length = 255)
    private String guestEmail;

    @Column(name = "guest_phone", nullable = false, length = 20)
    private String guestPhone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fulfilment_branch_id", nullable = false)
    private Branch fulfilmentBranch;

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

    /** BODA or VEHICLE for DELIVERY_COURIER orders; null for pickup and for orders that pre-date V14. */
    @Column(name = "delivery_method", length = 20)
    private String deliveryMethod;

    @Column(name = "placed_at")
    private Instant placedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CustomerOrderItem> items = new ArrayList<>();
}
