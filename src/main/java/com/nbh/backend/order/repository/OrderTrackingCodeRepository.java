package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.OrderTrackingCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderTrackingCodeRepository extends JpaRepository<OrderTrackingCode, UUID> {

    /**
     * The newest code for an email, LOCKED. Only used to check a guess: without the lock, guesses made
     * at the same moment each read the same {@code attempts}, each add one and overwrite one another, so
     * "five wrong guesses burn the code" would really mean "five rounds of as many parallel guesses as
     * the attacker can send". With it, guesses queue up and each sees the count the last one left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OrderTrackingCode> findFirstByEmailOrderByCreatedAtDesc(String email);

    @Modifying
    @Query("delete from OrderTrackingCode c where c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
