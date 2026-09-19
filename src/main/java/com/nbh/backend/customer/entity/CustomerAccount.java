package com.nbh.backend.customer.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * A shopper's account. Deliberately NOT a staff user: it carries the single
 * authority {@code ROLE_CUSTOMER} and none of the {@code PERM_*} authorities
 * that gate every staff endpoint, and {@code SecurityConfig} additionally
 * refuses that authority anywhere outside {@code /api/v1/customer/**}.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_account")
public class CustomerAccount extends BaseEntity implements UserDetails {

    public static final String AUTHORITY = "ROLE_CUSTOMER";

    /** Always stored trimmed and lower-cased. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(AUTHORITY));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
