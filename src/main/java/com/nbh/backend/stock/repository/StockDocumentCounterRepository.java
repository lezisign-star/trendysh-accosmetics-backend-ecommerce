package com.nbh.backend.stock.repository;

import com.nbh.backend.stock.entity.StockDocumentCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Backs {@link com.nbh.backend.stock.service.StockDocumentNoGenerator}'s
 * atomic per-(type, day) counter (audit finding F10, session 3). A single
 * upsert-and-increment statement is race-free under Postgres's row-level
 * locking — no application-level lock or retry needed, unlike the balance-row
 * race the ledger core has to worry about (there is no read to reconcile with;
 * this is a pure "give me the next number" operation).
 */
public interface StockDocumentCounterRepository extends JpaRepository<StockDocumentCounter, String> {

    @Modifying
    @Query(value = """
        INSERT INTO stock_document_counter (counter_key, next_value)
        VALUES (:counterKey, 1)
        ON CONFLICT (counter_key)
        DO UPDATE SET next_value = stock_document_counter.next_value + 1
        """, nativeQuery = true)
    void upsertAndIncrement(@Param("counterKey") String counterKey);

    @Query(value = "SELECT next_value FROM stock_document_counter WHERE counter_key = :counterKey", nativeQuery = true)
    long currentValue(@Param("counterKey") String counterKey);
}
