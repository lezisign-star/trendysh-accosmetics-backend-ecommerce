package com.nbh.backend.staff.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Staff/admin accounts only — NBH has no separate customer-auth system yet
 * (that lands in the Phase 2 e-commerce backend). Adapted from Moments
 * Packaging's User entity, stripped of everything customer-specific
 * (demographics, industry, delivery address, OTP/email-verification state)
 * since none of that applies to a staff login.
 */
@Getter
@Setter
@Entity
@Table(name = "staff_user")
public class StaffUser extends BaseEntity implements UserDetails {

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "staff_role_id")
    private StaffRole staffRole;

    /** True on first login with a temp password — frontend must force a change. */
    @Column(nullable = false)
    private boolean mustChangePassword = false;

    private Instant tempPasswordExpiresAt;

    @Column(nullable = false)
    private boolean deleted = false;

    private Instant deletedAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new HashSet<>();
        if (staffRole != null) {
            staffRole.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p.name())));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + staffRole.getName()));
        }
        return authorities;
    }

    public Set<Permission> getResolvedPermissions() {
        return staffRole == null ? Set.of() : staffRole.getPermissions();
    }

    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled && !deleted; }

    public String getFullName() { return firstName + " " + lastName; }
}
