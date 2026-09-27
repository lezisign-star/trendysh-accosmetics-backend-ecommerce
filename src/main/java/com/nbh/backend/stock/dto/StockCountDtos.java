package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountLine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 2 (2026-09-27) DTOs for the stock-count workflow. */
public class StockCountDtos {

    public record CreateCountRequest(
            @NotEmpty List<UUID> variantIds, boolean blind, String reason, UUID assignedTo) {}

    public record RecordCountRequest(@NotNull BigDecimal countedQty, String note) {}

    public record ApplyCountRequest(java.util.Map<UUID, String> resolutions) {}

    /**
     * {@code systemQtyAtStart} is null when the count is blind and the caller lacks
     * PERM_STOCK_ADJUST — counting against a visible target defeats the point of counting.
     */
    public record StockCountLineDto(
            UUID variantId, String sku, String variantName, String productName,
            BigDecimal systemQtyAtStart, BigDecimal countedQty, String note, boolean counted
    ) {
        public static StockCountLineDto from(StockCountLine line, boolean revealSystemQty) {
            var variant = line.getVariant();
            return new StockCountLineDto(
                    variant.getId(), variant.getSku(), variant.getVariantName(), variant.getProduct().getName(),
                    revealSystemQty ? line.getSystemQtyAtStart() : null,
                    line.getCountedQty(), line.getNote(), line.isCounted());
        }
    }

    public record StockCountDto(
            UUID id, String countNo, String status, boolean blind, String reason,
            UUID assignedToUserId, UUID createdByUserId, UUID appliedByUserId, Instant appliedAt,
            String appliedDocumentNo, List<StockCountLineDto> lines
    ) {
        public static StockCountDto from(StockCount count, boolean revealSystemQty) {
            return new StockCountDto(
                    count.getId(), count.getCountNo(), count.getStatus().name(), count.isBlind(), count.getReason(),
                    count.getAssignedToUserId(), count.getCreatedByUserId(), count.getAppliedByUserId(), count.getAppliedAt(),
                    count.getAppliedDocument() != null ? count.getAppliedDocument().getDocumentNo() : null,
                    count.getLines().stream().map(l -> StockCountLineDto.from(l, revealSystemQty)).toList());
        }
    }
}
