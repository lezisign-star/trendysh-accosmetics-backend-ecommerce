package com.nbh.backend.payment.repository;

import com.nbh.backend.payment.entity.PaymentSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, UUID> {
    /** There is only ever one row (see {@code PaymentSettings}'s javadoc) — this is that row. */
    Optional<PaymentSettings> findFirstByOrderByCreatedAtAsc();
}
