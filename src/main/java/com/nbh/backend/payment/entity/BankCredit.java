package com.nbh.backend.payment.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Money that arrived in the bank account (V20), matched to an order or left for staff to allocate. */
@Getter
@Setter
@Entity
@Table(name = "bank_credit")
public class BankCredit extends BaseEntity {

    @Column(name = "bank_txn_id", nullable = false, unique = true, length = 100)
    private String bankTxnId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payer_name", length = 200)
    private String payerName;

    @Column(name = "payer_phone", length = 30)
    private String payerPhone;

    @Column(columnDefinition = "TEXT")
    private String narration;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditStatus status = CreditStatus.UNMATCHED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_record_id")
    private PaymentRecord payment;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    public enum Source { MANUAL, IPN, RECONCILIATION }

    public enum CreditStatus { UNMATCHED, MATCHED, IGNORED }
}
