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
}
