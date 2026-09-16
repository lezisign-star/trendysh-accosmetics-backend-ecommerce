package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.OrderCounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Same atomic upsert-and-increment pattern as StockDocumentCounterRepository — see its javadoc for why. */
public interface OrderCounterRepository extends JpaRepository<OrderCounter, String> {

    @Modifying
    @Query(value = """
        INSERT INTO order_counter (counter_key, next_value)
        VALUES (:counterKey, 1)
        ON CONFLICT (counter_key)
        DO UPDATE SET next_value = order_counter.next_value + 1
        """, nativeQuery = true)
    void upsertAndIncrement(@Param("counterKey") String counterKey);

    @Query(value = "SELECT next_value FROM order_counter WHERE counter_key = :counterKey", nativeQuery = true)
    long currentValue(@Param("counterKey") String counterKey);
}
