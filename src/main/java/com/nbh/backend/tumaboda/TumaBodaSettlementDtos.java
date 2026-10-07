package com.nbh.backend.tumaboda;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class TumaBodaSettlementDtos {

    private TumaBodaSettlementDtos() {}

    /** One costed delivery still owed to TumaBoda. */
    public record OutstandingLine(UUID orderId, String orderNo, String customerName, BigDecimal tumabodaCost,
                                   Instant placedAt) {}

    public record OutstandingSummary(java.util.List<OutstandingLine> lines, BigDecimal totalOwed) {}

    public record SettlementDto(UUID id, LocalDate settledAt, BigDecimal amount, String reference, String notes,
                                 long orderCount, Instant createdAt) {
        static SettlementDto from(TumaBodaSettlement s, long orderCount) {
            return new SettlementDto(s.getId(), s.getSettledAt(), s.getAmount(), s.getReference(), s.getNotes(),
                    orderCount, s.getCreatedAt());
        }
    }

    /**
     * {@code amount} is what the bank statement shows — the source of truth, not a figure the server re-derives.
     * A settlement always clears everything owed, so an amount that is not what is owed would lose the
     * difference without a trace; it is refused unless {@code confirmDifference} says the difference is real.
     */
    public record RecordSettlementRequest(@NotNull LocalDate settledAt, @NotNull @Positive BigDecimal amount,
                                           String reference, String notes, boolean confirmDifference) {}
}
