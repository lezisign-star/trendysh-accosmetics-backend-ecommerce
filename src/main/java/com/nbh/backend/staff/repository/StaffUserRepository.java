package com.nbh.backend.staff.repository;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
    Optional<StaffUser> findByEmailIgnoreCaseAndDeletedFalse(String email);
    boolean existsByEmailIgnoreCase(String email);
    List<StaffUser> findByDeletedFalseOrderByCreatedAtDesc();

    /** Everyone the stock-alert emails go to: enabled accounts that haven't been switched off. */
    List<StaffUser> findByEnabledTrueAndDeletedFalseAndReceivesStockAlertsTrue();

    /**
     * Holders of a role other than {@code id} who can actually sign in today — enabled, and past their first sign-in (an
     * invited or env-seeded account that has never set a password is "pending" and cannot stand in for an owner). Used
     * to keep at least one Super Admin around: found in the 2026-10-06 go-live QA, the never-used placeholder admin
     * counted as "another Super Admin", so the real owner could be demoted with nobody left able to run the shop.
     */
    long countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndMustChangePasswordFalseAndIdNot(String roleName, UUID id);
}
