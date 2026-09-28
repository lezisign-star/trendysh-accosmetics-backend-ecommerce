package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.OrderTrackingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderTrackingSessionRepository extends JpaRepository<OrderTrackingSession, UUID> {

    Optional<OrderTrackingSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from OrderTrackingSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
