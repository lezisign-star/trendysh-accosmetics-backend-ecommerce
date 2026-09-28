package com.nbh.backend.order.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A single-use, expiring 6-digit code emailed to prove the mailbox before a guest can list their orders (V36). */
@Getter
@Setter
@Entity
@Table(name = "order_tracking_code")
public class OrderTrackingCode extends BaseEntity {

    /** Lower-cased. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** Wrong guesses against this code; it is burned at the limit. */
    @Column(nullable = false)
    private int attempts = 0;
}
