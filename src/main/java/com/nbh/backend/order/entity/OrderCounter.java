package com.nbh.backend.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Backs {@link com.nbh.backend.order.service.OrderNoGenerator}'s atomic per-day counter. */
@Getter
@Setter
@Entity
@Table(name = "order_counter")
public class OrderCounter {

    @Id
    @Column(name = "counter_key", length = 30)
    private String counterKey;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
