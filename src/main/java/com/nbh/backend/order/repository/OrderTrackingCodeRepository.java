package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.OrderTrackingCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OrderTrackingCodeRepository extends JpaRepository<OrderTrackingCode, UUID> {

    Optional<OrderTrackingCode> findFirstByEmailOrderByCreatedAtDesc(String email);

    @Modifying
    @Query("delete from OrderTrackingCode c where c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
