package com.nbh.backend.customer.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One link in a shopper's refresh chain. Only the SHA-256 hash of the cookie value is stored. */
@Getter
@Setter
@Entity
@Table(name = "customer_refresh_token")
public class CustomerRefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private CustomerAccount account;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** All tokens descended from one sign-in share a family, so theft can revoke the lot. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Swapped for its successor by a normal refresh. Reusing it soon after is a tab race; later is theft. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** Ended by logout, password change or theft detection. Never usable again. */
    @Column(name = "revoked_at")
    private Instant revokedAt;
}
