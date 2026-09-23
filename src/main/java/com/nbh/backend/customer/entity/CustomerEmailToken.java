package com.nbh.backend.customer.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single-use, expiring token: a link for VERIFY_EMAIL, a 6-digit OTP for
 * RESET_PASSWORD. Only the SHA-256 hash of the raw value is ever stored, and
 * lookups are scoped by account + purpose (not a global hash lookup) since an
 * OTP's narrow code space makes table-wide uniqueness impractical.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_email_token")
public class CustomerEmailToken extends BaseEntity {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private CustomerAccount account;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /** Wrong-code guesses against this row; RESET_PASSWORD is locked out (used-up) at MAX_ATTEMPTS. */
    @Column(nullable = false)
    private int attempts = 0;
}
