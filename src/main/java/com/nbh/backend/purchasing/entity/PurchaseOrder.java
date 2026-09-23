package com.nbh.backend.purchasing.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A supplier order the shop is expecting stock against — DRAFT while still being put
 * together, SENT once the supplier has it, then PARTIALLY_RECEIVED/RECEIVED as goods
 * actually arrive. Receiving posts a real {@code StockDocument} (GOODS_RECEIPT) through
 * {@code StockPostingService} exactly like the plain "just add stock" flow does — this
 * entity never touches stock quantities itself, only tracks what was ordered vs received.
 */
@Getter
@Setter
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends BaseEntity {

    @Column(name = "po_number", nullable = false, unique = true, length = 40)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(length = 1000)
    private String notes;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<PurchaseOrderLine> lines = new ArrayList<>();
}
