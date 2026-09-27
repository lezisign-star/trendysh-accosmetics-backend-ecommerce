package com.nbh.backend.stock.service;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.stock.dto.StockDocumentDetailDto;
import com.nbh.backend.stock.dto.StockMovementDto;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockDocumentRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 (2026-09-27): the ledger has been append-only, complete and correct since well
 * before this session — nothing had ever read it back for a human. This is that reader,
 * kept as its own small class rather than folded into {@link StockAvailabilityService}: that
 * class answers "what's the current position", this one answers "how did it get there" — a
 * different read concern, not just a different method.
 */
@Service
public class StockMovementQueryService {

    private final StockLedgerEntryRepository ledgerRepository;
    private final StockDocumentRepository documentRepository;

    public StockMovementQueryService(StockLedgerEntryRepository ledgerRepository,
                                      StockDocumentRepository documentRepository) {
        this.ledgerRepository = ledgerRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public Page<StockMovementDto> search(UUID variantId, StockDocumentType documentType,
                                          Instant from, Instant to, Pageable pageable) {
        // See StockLedgerEntryRepository.search's javadoc: from/to must always be real,
        // bindable values — never null — to avoid Postgres's parameter-type-inference failure
        // on a null Instant in a >=/<= comparison.
        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.now();
        return ledgerRepository.search(variantId, documentType, effectiveFrom, effectiveTo, pageable)
                .map(StockMovementDto::from);
    }

    @Transactional(readOnly = true)
    public StockDocumentDetailDto document(UUID documentId) {
        StockDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("No such stock document: " + documentId));
        var lines = ledgerRepository.findByDocumentIdOrderByIdAsc(documentId).stream()
                .map(StockMovementDto::from)
                .toList();
        return StockDocumentDetailDto.from(document, lines);
    }
}
