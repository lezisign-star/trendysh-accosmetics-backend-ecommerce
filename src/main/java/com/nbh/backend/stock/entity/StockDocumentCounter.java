package com.nbh.backend.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Backs the atomic per-(document type, day) numbering scheme (audit finding
 * F10, session 3) — see
 * {@link com.nbh.backend.stock.repository.StockDocumentCounterRepository}
 * for the actual upsert-and-increment query; this class exists only so Spring
 * Data JPA has a real mapped entity to attach that repository to.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_document_counter")
public class StockDocumentCounter {

    @Id
    @Column(name = "counter_key", length = 30)
    private String counterKey;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
