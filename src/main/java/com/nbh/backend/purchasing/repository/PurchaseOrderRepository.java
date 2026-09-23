package com.nbh.backend.purchasing.repository;

import com.nbh.backend.purchasing.entity.PurchaseOrder;
import com.nbh.backend.purchasing.entity.PurchaseOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("""
            SELECT po FROM PurchaseOrder po
            WHERE :status IS NULL OR po.status = :status
            """)
    Page<PurchaseOrder> search(@Param("status") PurchaseOrderStatus status, Pageable pageable);
}
