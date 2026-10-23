package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.OrderPriceChange;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Price changes on orders that staff enter for a customer (owner, 2026-10-23). See {@code OrderPriceChangeService}. */
public final class PriceChangeDtos {

    private PriceChangeDtos() {}

    /**
     * One line of the staff member's basket whose price they are changing, by the line's id in that basket. {@code reason} is
     * always needed. Unless the person entering the order may approve price changes themselves, {@code confirmerId} (the
     * owner or manager they agreed it with) and {@code whatWasSaid} (what was said between them) are needed too.
     */
    public record PriceChangeInput(
            @NotNull UUID cartItemId,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 2) BigDecimal newUnitPrice,
            @NotBlank @Size(max = 500) String reason,
            UUID confirmerId,
            @Size(max = 1000) String whatWasSaid
    ) {}

    /**
     * A recorded price change as staff read it. {@code status} is NOT_NEEDED, PENDING (waiting for {@code confirmer}),
     * CONFIRMED or DISPUTED; {@code answeredBy}, {@code answeredAt} and {@code answerNote} say who answered and what they said.
     */
    public record PriceChangeView(
            UUID id, UUID orderId, String orderNo, UUID itemId, String line, BigDecimal qty,
            BigDecimal listUnitPrice, BigDecimal newUnitPrice, BigDecimal amountOff,
            String reason, String enteredBy, String confirmer, String whatWasSaid,
            String status, Instant answeredAt, String answeredBy, String answerNote, Instant createdAt) {

        public static PriceChangeView from(OrderPriceChange c) {
            return new PriceChangeView(c.getId(), c.getOrder().getId(), c.getOrder().getOrderNo(), c.getOrderItem().getId(),
                    c.getLineLabel(), c.getQty(), c.getListUnitPrice(), c.getNewUnitPrice(), c.getAmountOff(),
                    c.getReason(), c.getEnteredByName(), c.getConfirmerName(), c.getWhatWasSaid(),
                    c.getAckStatus().name(), c.getAckAt(), c.getAckByName(), c.getAckNote(), c.getCreatedAt());
        }
    }

    /** Someone who may be named as the person who agreed a price change. */
    public record Approver(UUID id, String name, String role) {}

    /** The answer to a price change: a note is needed to dispute one, optional to confirm it. */
    public record Answer(@Size(max = 1000) String note) {}

    /** How many price changes are waiting for this person's answer. */
    public record Waiting(long count) {}
}
