package com.nbh.backend.customer.repository;

import com.nbh.backend.customer.entity.CustomerEmailToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerEmailTokenRepository extends JpaRepository<CustomerEmailToken, UUID> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "account")
    Optional<CustomerEmailToken> findByTokenHash(String tokenHash);

    /** The most recent token of a kind for an account — used to rate-limit re-sends. */
    Optional<CustomerEmailToken> findFirstByAccountIdAndPurposeOrderByCreatedAtDesc(
            UUID accountId, CustomerEmailToken.Purpose purpose);

    /**
     * The same row, LOCKED, for checking a guess at a code: unlocked, guesses made at the same moment
     * each read the same {@code attempts} and overwrite one another's increment, so the five-guess limit
     * would not actually limit parallel guessing. (Named differently from the finder above only because
     * Spring Data derives both from their names.)
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<CustomerEmailToken> findTopByAccountIdAndPurposeOrderByCreatedAtDesc(
            UUID accountId, CustomerEmailToken.Purpose purpose);
}
