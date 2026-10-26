package com.nbh.backend.stock.dto;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountLine;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phase 2 (2026-09-27) DTOs for the stock-count workflow. */
public class StockCountDtos {

    public record CreateCountRequest(
            @NotEmpty List<UUID> variantIds, boolean blind, String reason, UUID assignedTo) {}

    /**
     * A stock take of a part of the shop (owner, 2026-10-26): {@code everything}, or these categories (subcategories
     * included), or these products.
     */
    public record ScopeCountRequest(boolean everything, List<UUID> categoryIds, List<UUID> productIds, boolean blind,
                                    String reason) {}

    public record RecordCountRequest(@NotNull BigDecimal countedQty, String note) {}

    /** Several lines at once: variant id -> counted quantity ("set all its options to 20"). */
    public record RecordCountsRequest(@NotEmpty Map<UUID, BigDecimal> counted) {}

    public record ApplyCountRequest(java.util.Map<UUID, String> resolutions) {}

    /**
     * {@code systemQtyAtStart} is null when the count is blind and the caller lacks
     * PERM_STOCK_ADJUST — counting against a visible target defeats the point of counting.
     * {@code productId}, {@code option}, {@code categoryId}/{@code categoryName} (the product's main category, as its
     * top-level one) and {@code imageUrl} let the counting screen put a product's options together on one card, filter by
     * category and show what the thing looks like (2026-10-26).
     */
    public record StockCountLineDto(
            UUID variantId, String sku, String variantName, String productName,
            BigDecimal systemQtyAtStart, BigDecimal countedQty, String note, boolean counted,
            UUID productId, String option, UUID categoryId, String categoryName, String imageUrl
    ) {
        public static StockCountLineDto from(StockCountLine line, boolean revealSystemQty) {
            return from(line, revealSystemQty, null);
        }

        public static StockCountLineDto from(StockCountLine line, boolean revealSystemQty, String imageUrl) {
            var variant = line.getVariant();
            var product = variant.getProduct();
            Category main = product.getCategories().isEmpty() ? null : product.getCategories().get(0);
            Category top = main == null ? null : (main.getParent() != null ? main.getParent() : main);
            return new StockCountLineDto(
                    variant.getId(), variant.getSku(), variant.getVariantName(), product.getName(),
                    revealSystemQty ? line.getSystemQtyAtStart() : null,
                    line.getCountedQty(), line.getNote(), line.isCounted(),
                    product.getId(), variant.shortOption(),
                    top == null ? null : top.getId(), top == null ? null : top.getName(), imageUrl);
        }
    }

    public record StockCountDto(
            UUID id, String countNo, String status, boolean blind, String reason,
            UUID assignedToUserId, UUID createdByUserId, UUID appliedByUserId, Instant appliedAt,
            String appliedDocumentNo, List<StockCountLineDto> lines
    ) {
        public static StockCountDto from(StockCount count, boolean revealSystemQty) {
            return from(count, revealSystemQty, Map.of());
        }

        /** {@code images}: a photo address per variant id, for the counting screen (absent ones show none). */
        public static StockCountDto from(StockCount count, boolean revealSystemQty, Map<UUID, String> images) {
            return new StockCountDto(
                    count.getId(), count.getCountNo(), count.getStatus().name(), count.isBlind(), count.getReason(),
                    count.getAssignedToUserId(), count.getCreatedByUserId(), count.getAppliedByUserId(), count.getAppliedAt(),
                    count.getAppliedDocument() != null ? count.getAppliedDocument().getDocumentNo() : null,
                    count.getLines().stream()
                            .map(l -> StockCountLineDto.from(l, revealSystemQty, images.get(l.getVariant().getId())))
                            .toList());
        }
    }
}
