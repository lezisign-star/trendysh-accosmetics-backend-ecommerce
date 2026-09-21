package com.nbh.backend.payment.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A customer's "I've paid" statement (V20). Never auto-confirms anything; staff verify it against the bank. */
@Getter
@Setter
@Entity
@Table(name = "payment_claim")
public class PaymentClaim extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_record_id", nullable = false)
    private PaymentRecord payment;

    @Column(name = "transaction_code", nullable = false, length = 60)
    private String transactionCode;

    @Column(name = "amount_claimed", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountClaimed;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "payer_name", length = 200)
    private String payerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.PENDING;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    public enum ClaimStatus { PENDING, APPROVED, REJECTED }
}
