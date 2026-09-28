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

    /**
     * The same row, LOCKED, for checking a guess at a code: unlocked, guesses made at the same moment
     * each read the same {@code attempts} and overwrite one another's increment, so the five-guess limit
     * would not actually limit parallel guessing — on an account that can administer the whole shop.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<StaffPasswordResetToken> findTopByStaffIdOrderByCreatedAtDesc(UUID staffId);
}
