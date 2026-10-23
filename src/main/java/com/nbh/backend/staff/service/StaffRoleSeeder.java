package com.nbh.backend.staff.service;

import com.nbh.backend.staff.entity.Permission;
import com.nbh.backend.staff.entity.StaffRole;
import com.nbh.backend.staff.repository.StaffRoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Seeds default roles on every boot, and — for roles still flagged isDefault
 * (i.e. not customised by a SUPER_ADMIN since) — keeps their permission set in
 * sync with whatever the Permission enum currently contains. Adapted from
 * Moments Packaging's StaffRoleSeeder; the role list itself is NBH-specific.
 */
@Slf4j
@Component
public class StaffRoleSeeder {

    private final StaffRoleRepository staffRoleRepository;

    public StaffRoleSeeder(StaffRoleRepository staffRoleRepository) {
        this.staffRoleRepository = staffRoleRepository;
    }

    @PostConstruct
    public void seed() {
        Map<String, RoleDefinition> definitions = defaultRoleDefinitions();

        for (Map.Entry<String, RoleDefinition> entry : definitions.entrySet()) {
            String name = entry.getKey();
            RoleDefinition def = entry.getValue();

            StaffRole role = staffRoleRepository.findByName(name).orElseGet(StaffRole::new);
            boolean isNew = role.getId() == null;

            if (isNew || role.isDefault()) {
                role.setName(name);
                role.setDisplayName(def.displayName());
                role.setDescription(def.description());
                role.setDefault(true);
                role.setPermissions(def.permissions());
                role.setDeleted(false);
                staffRoleRepository.save(role);
                log.info("{} default staff role '{}' ({} permissions)",
                        isNew ? "Seeded" : "Synced", name, def.permissions().size());
            }
        }
    }

    private Map<String, RoleDefinition> defaultRoleDefinitions() {
        Map<String, RoleDefinition> roles = new LinkedHashMap<>();

        roles.put("SUPER_ADMIN", new RoleDefinition(
                "Super Admin",
                "Full access — every permission.",
                EnumSet.allOf(Permission.class)));

        // Functionally identical to SUPER_ADMIN (same full permission set) — only the name differs,
        // for the engineering/maintenance account(s) seeded alongside the shop owner's.
        roles.put("DEVELOPER", new RoleDefinition(
                "Developer",
                "Full access — every permission. For engineering/maintenance accounts.",
                EnumSet.allOf(Permission.class)));

        // Renamed from BRANCH_MANAGER 2026-09-24 when branches were removed (founder decision —
        // see HANDOFF.md); same responsibilities, minus the two transfer permissions that no
        // longer mean anything with one shop and nothing to transfer between.
        roles.put("MANAGER", new RoleDefinition(
                "Manager",
                "Runs day-to-day operations: stock, POS, orders, staff view.",
                EnumSet.of(
                        Permission.ORDER_VIEW, Permission.ORDER_MANAGE_ALL,
                        Permission.ORDER_ENTER, Permission.ORDER_PRICE_APPROVE,
                        Permission.PRODUCT_VIEW, Permission.PRODUCT_MANAGE,
                        Permission.STOCK_VIEW, Permission.STOCK_RECEIVE, Permission.STOCK_ADJUST,
                        Permission.STOCK_COUNT,
                        Permission.POS_SELL, Permission.POS_OPEN_SESSION, Permission.POS_CLOSE_SESSION,
                        Permission.POS_DISCOUNT, Permission.POS_VOID,
                        Permission.PAYMENT_VIEW, Permission.PAYMENT_SETTINGS_MANAGE,
                        Permission.USER_VIEW,
                        Permission.ANALYTICS_VIEW,
                        Permission.CUSTOMER_VIEW
                )));

        roles.put("CASHIER", new RoleDefinition(
                "Cashier",
                "Front-counter POS operator. Can enter an order for a customer who phones. No stock adjustment, no refunds, no settings.",
                EnumSet.of(
                        Permission.ORDER_VIEW, Permission.ORDER_ENTER,
                        Permission.PRODUCT_VIEW,
                        Permission.STOCK_VIEW,
                        Permission.POS_SELL, Permission.POS_OPEN_SESSION, Permission.POS_CLOSE_SESSION,
                        Permission.CUSTOMER_VIEW
                )));

        roles.put("STOCK_CONTROLLER", new RoleDefinition(
                "Stock Controller",
                "Receives goods and runs stock-takes. Can enter an order for a customer who phones; no POS, no other order or refund access.",
                EnumSet.of(
                        Permission.ORDER_ENTER,
                        Permission.PRODUCT_VIEW, Permission.PRODUCT_MANAGE,
                        Permission.STOCK_VIEW, Permission.STOCK_RECEIVE, Permission.STOCK_ADJUST,
                        Permission.STOCK_COUNT
                )));

        roles.put("VIEWER", new RoleDefinition(
                "Viewer",
                "Read-only access — reporting/oversight without any mutating permission.",
                EnumSet.of(
                        Permission.ORDER_VIEW, Permission.PRODUCT_VIEW, Permission.STOCK_VIEW,
                        Permission.PAYMENT_VIEW, Permission.ANALYTICS_VIEW, Permission.CUSTOMER_VIEW
                )));

        return roles;
    }

    private record RoleDefinition(String displayName, String description, Set<Permission> permissions) {}
}
