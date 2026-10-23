package com.nbh.backend.order.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A price changed on one line while staff entered an order for a customer (V72). The line keeps the price the customer was
 * charged; this keeps what the shop's own price was, why it was changed, who changed it, and the verbal OK of the owner or
 * manager they spoke to. Written once, when the order is placed, and never edited afterwards: the only thing that moves is
 * the named confirmer's answer ({@link #acknowledge}).
 */
@Getter
@Setter
@Entity
@Table(name = "order_price_change")
public class OrderPriceChange extends BaseEntity {

    /**
     * NOT_NEEDED: the person who changed it may approve price changes and named no one. PENDING: waiting for the named
     * confirmer. CONFIRMED / DISPUTED: their answer, which is final.
     */
    public enum Acknowledgement { NOT_NEEDED, PENDING, CONFIRMED, DISPUTED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private CustomerOrderItem orderItem;

    /** "Gel Polish (Ruby Red)" as it was ordered, so a list of price changes reads without opening each order. */
    @Column(name = "line_label", nullable = false, length = 300)
    private String lineLabel;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal qty;

    /** What the shop's own prices made it (deals and wholesale included), per unit. */
    @Column(name = "list_unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal listUnitPrice;

    /** What the customer was charged per unit. */
    @Column(name = "new_unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal newUnitPrice;

    /** (list - new) x qty: money given away, or negative when the price was raised. */
    @Column(name = "amount_off", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountOff;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "entered_by_id")
    private UUID enteredById;

    @Column(name = "entered_by_name", nullable = false, length = 200)
    private String enteredByName;

    @Column(name = "confirmer_id")
    private UUID confirmerId;

    @Column(name = "confirmer_name", length = 200)
    private String confirmerName;

    /** What was said between the staff member and the confirmer, in the staff member's words. */
    @Column(name = "what_was_said", length = 1000)
    private String whatWasSaid;

    @Enumerated(EnumType.STRING)
    @Column(name = "ack_status", nullable = false, length = 20)
    private Acknowledgement ackStatus;

    @Column(name = "ack_at")
    private Instant ackAt;

    @Column(name = "ack_by_id")
    private UUID ackById;

    @Column(name = "ack_by_name", length = 200)
    private String ackByName;

    @Column(name = "ack_note", length = 1000)
    private String ackNote;

    /** Whether the confirmer has yet to answer. */
    public boolean isWaiting() {
        return ackStatus == Acknowledgement.PENDING;
    }

    /** Records the answer of whoever confirms or disputes it. Only a change still waiting for an answer can be answered. */
    public void acknowledge(boolean confirmed, UUID byId, String byName, String note, Instant when) {
        if (!isWaiting()) {
            throw new IllegalStateException("This price change has already been answered.");
        }
        ackStatus = confirmed ? Acknowledgement.CONFIRMED : Acknowledgement.DISPUTED;
        ackById = byId;
        ackByName = byName;
        ackNote = note;
        ackAt = when;
    }
}
