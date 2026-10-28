package com.nbh.backend.delivery.transport;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Where one operator drops orders: typed by a shopper or added by staff, with the likely fare when known (V82). */
@Getter
@Setter
@Entity
@Table(name = "transport_stop")
public class TransportStop extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false)
    private TransportOperator operator;

    @Column(nullable = false, length = 120)
    private String name;

    /** The name reduced for matching (see {@link TransportStops#key}): a similar name finds the same stop. */
    @Column(name = "name_key", nullable = false, length = 120)
    private String nameKey;

    @Column(name = "fare_min", precision = 10, scale = 2)
    private BigDecimal fareMin;

    @Column(name = "fare_max", precision = 10, scale = 2)
    private BigDecimal fareMax;

    /** Staff set the fare (rather than nobody yet). */
    @Column(name = "fare_confirmed", nullable = false)
    private boolean fareConfirmed;

    /** How many orders went there: the most used are suggested first. */
    @Column(nullable = false)
    private int uses;

    /** Staff took it off the suggestions (a wrong or private entry); kept so it is not added again by the same text. */
    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "added_by", nullable = false, length = 10)
    private String addedBy = "CUSTOMER";
}
