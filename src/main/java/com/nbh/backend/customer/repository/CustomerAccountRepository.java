package com.nbh.backend.customer.repository;

import com.nbh.backend.customer.entity.CustomerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, UUID> {
    Optional<CustomerAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
