package com.nbh.backend.customer.repository;

import com.nbh.backend.customer.entity.CustomerRefreshToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRefreshTokenRepository extends JpaRepository<CustomerRefreshToken, UUID> {

    @EntityGraph(attributePaths = "account")
    Optional<CustomerRefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CustomerRefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CustomerRefreshToken t set t.revokedAt = :now where t.account.id = :accountId and t.revokedAt is null")
    int revokeAllForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Query("delete from CustomerRefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
