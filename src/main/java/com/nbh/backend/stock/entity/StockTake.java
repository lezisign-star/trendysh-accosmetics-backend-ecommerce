package com.nbh.backend.stock.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A stock take of the shop, category by category (V85): each top-level category is its own count. See StockTakeService. */
@Getter
@Setter
@Entity
@Table(name = "stock_take")
public class StockTake extends BaseEntity {

    @Column(name = "take_no", nullable = false, unique = true, length = 40)
    private String takeNo;

    /** OPEN while categories are being counted; CLOSED when staff end it. */
    @Column(nullable = false, length = 10)
    private String status = "OPEN";

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "closed_at")
    private Instant closedAt;
}
