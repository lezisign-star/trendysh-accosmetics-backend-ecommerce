package com.nbh.backend.staff.entity;

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

/** A single-use, expiring password-reset link for a staff account. Only the SHA-256 hash is stored. */
@Getter
@Setter
@Entity
@Table(name = "staff_password_reset_token")
public class StaffPasswordResetToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id", nullable = false)
    private StaffUser staff;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;
}
