package com.nbh.backend.stock.service;

import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockDocumentCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Human-readable document references (e.g. GRN-20260912-000001).
 *
 * Audit finding F10 (session 3): the previous version used a random 4-digit
 * suffix (9000 possible values per prefix per day). By the birthday bound,
 * ~120 documents of one type in a day already gives roughly a 55% chance of
 * a collision, and 300 POS sales in a day gives well over 99% — a
 * document_no UNIQUE violation mid-sale at the till. This version uses a real
 * atomic counter instead (see StockDocumentCounterRepository): the
 * upsert-and-increment and the read-back happen in ONE transaction, so the
 * row lock Postgres takes for the UPDATE is held across both statements —
 * no other caller can read a number this transaction is still in the middle
 * of claiming, and no two callers can ever receive the same one.
 *
 * §4 C6 fix: {@code next()} is {@code REQUIRES_NEW} — deliberately, not an
 * oversight. Called from inside a much larger caller transaction
 * (StockController.receive/adjust are not transactional themselves so this
 * already ran standalone there, but OrderFulfilmentService.fulfil's own
 * @Transactional method calls it as its first statement), REQUIRED
 * propagation would hold the counter row's lock for the FULL duration of
 * that outer transaction — every concurrent fulfilment in the whole system
 * would serialise behind one counter row. REQUIRES_NEW commits the counter
 * increment in its own microseconds-long transaction and releases the lock
 * immediately, before the expensive work even starts.
 *
 * <p><b>Trade-off, accepted deliberately:</b> if the outer transaction later
 * rolls back (e.g. {@code postingService.post} throws because stock moved
 * underneath), the document number this call claimed is now a permanent gap
 * — burned, never reused. Odoo and ERPNext both allow numbering gaps for
 * exactly this reason; it is the normal, correct trade-off for throughput.
 * <b>Do not "fix" this back to REQUIRED without a real reason</b> — if the
 * founder ever needs strictly gapless numbering for KRA purposes, that needs
 * a different design (assign the number at a later, commit-certain point,
 * e.g. from within the same transaction the document is marked POSTED in —
 * not a reversion of this one).
 */
@Service
public class StockDocumentNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockDocumentCounterRepository counterRepository;

    public StockDocumentNoGenerator(StockDocumentCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(StockDocumentType type) {
        String prefix = prefixFor(type);
        String counterKey = prefix + "-" + LocalDate.now().format(DATE);

        counterRepository.upsertAndIncrement(counterKey);
        long sequence = counterRepository.currentValue(counterKey);

        return counterKey + "-" + String.format("%06d", sequence);
    }

    private String prefixFor(StockDocumentType type) {
        return switch (type) {
            case GOODS_RECEIPT -> "GRN";
            case SALE_ONLINE -> "SO";
            case SALE_POS -> "POS";
            case CUSTOMER_RETURN -> "CR";
            case SUPPLIER_RETURN -> "SR";
            case TRANSFER -> "TRF";
            case ADJUSTMENT -> "ADJ";
            case STOCK_COUNT -> "SC";
            case WRITE_OFF -> "WO";
        };
    }
}
