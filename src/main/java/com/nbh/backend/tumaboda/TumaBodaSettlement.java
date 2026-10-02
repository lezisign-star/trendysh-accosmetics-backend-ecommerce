package com.nbh.backend.tumaboda;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One remittance event Trendysh makes to TumaBoda (TUMABODA_PARITY_PLAN.md Section B) — recording
 * it sweeps every currently-unsettled {@code CustomerOrder} with a costed TumaBoda delivery into
 * this settlement (see {@code TumaBodaSettlementService.record}). {@code amount} is what the bank
 * statement actually shows, not a sum the system computes — it is the source of truth for what was
 * paid; the swept orders are the record of what it was for.
 */
@Getter
@Setter
@Entity
@Table(name = "tumaboda_settlement")
public class TumaBodaSettlement extends BaseEntity {

    @Column(name = "settled_at", nullable = false)
    private LocalDate settledAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(length = 100)
    private String reference;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;
}
