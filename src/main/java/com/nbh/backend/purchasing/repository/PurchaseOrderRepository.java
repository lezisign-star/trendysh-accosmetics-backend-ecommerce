package com.nbh.backend.purchasing.repository;

import com.nbh.backend.purchasing.entity.PurchaseOrder;
import com.nbh.backend.purchasing.entity.PurchaseOrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    @Query("""
            SELECT po FROM PurchaseOrder po
            WHERE :status IS NULL OR po.status = :status
            """)
    Page<PurchaseOrder> search(@Param("status") PurchaseOrderStatus status, Pageable pageable);

    /**
     * Phase 0 fix (2026-09-27), D2: {@code markSent}/{@code cancel}/{@code receive} all
     * loaded the PO unlocked before mutating it — two concurrent receives against the same
     * PO could both pass the remaining-quantity check on their own stale read, both post a
     * GOODS_RECEIPT, and the qtyReceived update itself would then lose one of the two
     * increments (last write wins), leaving the ledger showing twice the goods received
     * while the PO shows only once. No fetch join here on purpose — Postgres rejects
     * {@code FOR UPDATE} on the nullable side of an outer join; the lines collection loads
     * as its own separate (unlocked, but that's fine — they're owned by this now-locked
     * parent) query when accessed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrder po where po.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") UUID id);
}
