package com.nbh.backend.staff.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * A named, data-driven role carrying a set of {@link Permission}s — adapted from
 * Moments Packaging's StaffRole. Default roles are seeded by StaffRoleSeeder on
 * every boot (and kept in sync as new Permission values are added); SUPER_ADMIN
 * may create custom roles beyond the defaults.
 */
@Getter
@Setter
@Entity
@Table(name = "staff_role")
public class StaffRole extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean isDefault = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "staff_role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", length = 50)
    private Set<Permission> permissions = new HashSet<>();

    @Column(nullable = false)
    private boolean deleted = false;
}
