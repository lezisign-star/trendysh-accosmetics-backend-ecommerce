package com.nbh.backend.customer.repository;

import com.nbh.backend.customer.entity.CustomerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, UUID> {
    Optional<CustomerAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** Keeps the delivery a shopper just used for their next order (see CustomerAccount.savedDelivery). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update CustomerAccount c set c.savedDelivery = :saved where c.id = :id")
    int saveDelivery(@org.springframework.data.repository.query.Param("id") UUID id,
                     @org.springframework.data.repository.query.Param("saved") String saved);
}
