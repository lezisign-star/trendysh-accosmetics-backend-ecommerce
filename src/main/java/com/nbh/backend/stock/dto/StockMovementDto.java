package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockLedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 (2026-09-27): one row in the movement-history view — the ledger has been complete
 * and correct since Phase 0/before, but nothing read it. Deliberately does not expose the
 * ledger's own {@code id} (a bare BIGSERIAL, meaningless to staff) — the document number is
 * the human-facing reference.
 */
public record StockMovementDto(
        UUID variantId,
        String sku,
        String variantName,
        String productName,
        String account,
        Instant postedAt,
        BigDecimal qtyChange,
        BigDecimal qtyAfter,
        BigDecimal unitCost,
        BigDecimal stockValueDifference,
        String documentNo,
        String documentType,
        String referenceType,
        UUID referenceId,
        String notes
) {
    public static StockMovementDto from(StockLedgerEntry e) {
        var variant = e.getVariant();
        var document = e.getDocument();
        return new StockMovementDto(
                variant.getId(), variant.getSku(), variant.getVariantName(), variant.getProduct().getName(),
                e.getAccount().name(), e.getPostedAt(), e.getQtyChange(), e.getQtyAfter(),
                e.getUnitCost(), e.getStockValueDifference(),
                document.getDocumentNo(), document.getDocumentType().name(),
                document.getReferenceType(), document.getReferenceId(), document.getNotes());
    }
}
