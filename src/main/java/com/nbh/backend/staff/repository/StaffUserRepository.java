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

    /** Active holders of a role other than {@code id} — used to keep at least one Super Admin around. */
    long countByStaffRoleNameAndEnabledTrueAndDeletedFalseAndIdNot(String roleName, UUID id);
}
