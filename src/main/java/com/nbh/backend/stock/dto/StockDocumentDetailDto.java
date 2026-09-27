package com.nbh.backend.stock.dto;

import com.nbh.backend.stock.entity.StockDocument;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 1 (2026-09-27): {@code GET /stock/documents/{id}} — the document header plus its own ledger entries. */
public record StockDocumentDetailDto(
        UUID id,
        String documentNo,
        String documentType,
        String status,
        Instant postedAt,
        String referenceType,
        UUID referenceId,
        String notes,
        List<StockMovementDto> lines
) {
    public static StockDocumentDetailDto from(StockDocument document, List<StockMovementDto> lines) {
        return new StockDocumentDetailDto(
                document.getId(), document.getDocumentNo(), document.getDocumentType().name(),
                document.getStatus().name(), document.getPostedAt(),
                document.getReferenceType(), document.getReferenceId(), document.getNotes(), lines);
    }
}
