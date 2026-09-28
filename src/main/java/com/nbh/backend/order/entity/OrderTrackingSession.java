package com.nbh.backend.order.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** What a verified code buys: a short-lived bearer token (stored hashed) that may read this email's orders (V36). */
@Getter
@Setter
@Entity
@Table(name = "order_tracking_session")
public class OrderTrackingSession extends BaseEntity {

    /** Lower-cased. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
