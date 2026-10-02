package com.nbh.backend.delivery;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "delivery_place_price")
public class DeliveryPlacePrice extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private DeliveryPlace place;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryMethod method;

    @Column(name = "price_min", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceMin;

    @Column(name = "price_max", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceMax;

    @Column(name = "eta_text", length = 100)
    private String etaText;

    @Column(length = 300)
    private String notes;
}
