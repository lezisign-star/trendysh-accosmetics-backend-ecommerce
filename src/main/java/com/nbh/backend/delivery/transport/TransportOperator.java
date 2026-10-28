package com.nbh.backend.delivery.transport;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A Metro operator: a sacco or company running minibuses (Super Metro, Nicco...), V82. */
@Getter
@Setter
@Entity
@Table(name = "transport_operator")
public class TransportOperator extends BaseEntity {

    @Column(nullable = false, length = 80)
    private String name;

    /** Where it runs, in staff's words ("Thika Road, Ruiru, Juja"); shown under its name. */
    @Column(length = 300)
    private String areas;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    /** How many orders chose it: the checkout lists the most chosen first. */
    @Column(nullable = false)
    private int picks;
}
