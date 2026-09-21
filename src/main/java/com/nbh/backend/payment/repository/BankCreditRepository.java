package com.nbh.backend.payment.repository;

import com.nbh.backend.payment.entity.BankCredit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankCreditRepository extends JpaRepository<BankCredit, UUID> {

    Optional<BankCredit> findByBankTxnId(String bankTxnId);

    @EntityGraph(attributePaths = {"payment", "payment.order"})
    List<BankCredit> findByStatusOrderByReceivedAtDesc(BankCredit.CreditStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BankCredit c where c.id = :id")
    Optional<BankCredit> lockById(UUID id);
}
