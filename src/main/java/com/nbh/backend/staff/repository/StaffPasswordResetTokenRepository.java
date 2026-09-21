package com.nbh.backend.staff.repository;

import com.nbh.backend.staff.entity.StaffPasswordResetToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffPasswordResetTokenRepository extends JpaRepository<StaffPasswordResetToken, UUID> {

    @EntityGraph(attributePaths = "staff")
    Optional<StaffPasswordResetToken> findByTokenHash(String tokenHash);

    /** The most recent link for a staff member — used to rate-limit re-sends. */
    Optional<StaffPasswordResetToken> findFirstByStaffIdOrderByCreatedAtDesc(UUID staffId);
}
