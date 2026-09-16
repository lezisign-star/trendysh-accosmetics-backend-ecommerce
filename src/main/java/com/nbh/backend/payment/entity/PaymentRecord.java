package com.nbh.backend.payment.entity;

import com.nbh.backend.common.entity.BaseEntity;
import com.nbh.backend.order.entity.CustomerOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * Append-only attempt log — mirrors Moments Packaging's PaymentRecord: order
 * state and payment state are separate tables, every attempt (success or
 * failure) is recorded, never overwritten. rawRequest/rawResponse use
 * Hibernate 6's built-in JSON mapping (no extra dependency needed) onto the
 * migration's jsonb columns — useful for debugging a disputed payment months
 * later without needing to have logged it elsewhere.
 */
@Getter
@Setter
@Entity
@Table(name = "payment_record")
public class PaymentRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.INITIATED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_request")
    private String rawRequest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response")
    private String rawResponse;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    public enum PaymentMethod { MPESA, CARD, CASH, BANK }

    public enum PaymentStatus { INITIATED, PENDING, SUCCESS, FAILED, REVERSED }
}
