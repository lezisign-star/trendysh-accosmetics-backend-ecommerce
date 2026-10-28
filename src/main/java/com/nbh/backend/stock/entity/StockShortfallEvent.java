package com.nbh.backend.stock.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A shopper the shop refused for stock (V81): a critical alert for staff until a person marks it handled. */
@Getter
@Setter
@Entity
@Table(name = "stock_shortfall_event")
public class StockShortfallEvent extends BaseEntity {

    public enum Source { CART, CHECKOUT }

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "product_name", nullable = false, length = 300)
    private String productName;

    @Column(name = "qty_asked", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyAsked;

    @Column(name = "qty_left", nullable = false, precision = 14, scale = 3)
    private BigDecimal qtyLeft;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(name = "session_ref", length = 100)
    private String sessionRef;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "handled_by", length = 120)
    private String handledBy;
}
