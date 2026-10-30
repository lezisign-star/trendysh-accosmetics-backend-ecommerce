package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockCount;
import com.nbh.backend.stock.entity.StockCountLine;
import com.nbh.backend.stock.entity.StockCountStatus;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockCountRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2 (2026-09-27): physical stock counts — the one core inventory workflow this system had
 * no real path for at all. A count freezes each line's {@code systemQtyAtStart} at creation time;
 * applying compares that frozen snapshot against the THEN-current on-hand (locked fresh, not
 * re-read from the snapshot) and requires an explicit resolution for any line where they now
 * differ — stock genuinely moved between the count and the apply, and a person decides whether
 * to trust the fresh count or preserve the originally-observed difference, rather than either
 * being silently overwritten.
 *
 * <p>Not yet built: a distinct maker-checker requirement (applier must differ from every
 * counter) — the design doc calls this an explicit open decision for the business owner, and
 * this codebase has no established maker-checker enforcement to extend yet (see the Phase 0
 * finding that adjustments/write-offs have none either). {@code countedByUserId} is tracked per
 * line regardless, so enforcing it later is a service-layer check away, not a schema change.
 */
@Service
public class StockCountService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Which figure to keep when a line's on-hand has drifted since the count was taken. */
    public enum ConflictResolution { KEEP_COUNTED, KEEP_DIFFERENCE }

    private final StockCountRepository countRepository;
    private final ProductVariantRepository variantRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;
    private final Clock clock;

    public StockCountService(StockCountRepository countRepository,
                              ProductVariantRepository variantRepository,
                              StockBalanceRepository balanceRepository,
                              StockPostingService postingService,
                              StockDocumentNoGenerator documentNoGenerator,
                              Clock clock) {
        this.countRepository = countRepository;
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StockCount get(UUID id) {
        return countRepository.findById(id).orElseThrow(() -> new NotFoundException("No such stock count: " + id));
    }

    @Transactional(readOnly = true)
    public Page<StockCount> list(StockCountStatus status, Pageable pageable) {
        return countRepository.search(status, pageable);
    }

    /** Creates a DRAFT count, one line per variant, each frozen at its current on-hand. */
    @Transactional
    public StockCount create(List<UUID> variantIds, boolean blind, String reason, UUID assignedTo, UUID staffUserId) {
        if (variantIds == null || variantIds.isEmpty()) {
            throw new IllegalArgumentException("A count needs at least one variant");
        }
        List<ProductVariant> variants = variantRepository.findAllById(variantIds);
        if (variants.size() != Set.copyOf(variantIds).size()) {
            throw new EntityNotFoundException("One or more variants not found");
        }

        StockCount count = new StockCount();
        count.setCountNo("SC-" + LocalDate.now(clock).format(DATE) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        count.setBlind(blind);
        count.setReason(reason);
        count.setAssignedToUserId(assignedTo);
        count.setCreatedByUserId(staffUserId);

        for (ProductVariant variant : variants) {
            StockCountLine line = new StockCountLine();
            line.setStockCount(count);
            line.setVariant(variant);
            line.setSystemQtyAtStart(currentOnHand(variant.getId()));
            count.getLines().add(line);
        }

        StockCount saved = countRepository.save(count);
        // Force-initialise variant.product before this transaction closes — save() returns the
        // entity as freshly built (variants came from a plain findAllById, not the @EntityGraph
        // findById below), so a lazy read of .getProduct() from the controller's DTO mapper
        // would otherwise fail once the session is gone.
        saved.getLines().forEach(l -> l.getVariant().getProduct().getName());
        return saved;
    }

    /**
     * A stock take of a whole part of the shop (owner, 2026-10-26): every real option in the shop, or of these categories
     * (subcategories included), or of these products. See {@link ProductVariantRepository#idsInScope}.
     */
    @Transactional
    public StockCount createForScope(boolean everything, List<UUID> categoryIds, List<UUID> productIds, boolean blind,
                                     String reason, UUID staffUserId) {
        List<UUID> cats = categoryIds == null || categoryIds.isEmpty() ? List.of(NO_ID) : categoryIds;
        List<UUID> prods = productIds == null || productIds.isEmpty() ? List.of(NO_ID) : productIds;
        if (!everything && cats.get(0).equals(NO_ID) && prods.get(0).equals(NO_ID)) {
            throw new IllegalArgumentException("Choose what to count: the whole shop, some categories or some products");
        }
        List<UUID> variantIds = variantRepository.idsInScope(everything, cats, prods);
        if (variantIds.isEmpty()) {
            throw new UnprocessableException("There is nothing to count in that choice");
        }
        return create(variantIds, blind, reason, null, staffUserId);
    }

    /** Stands in for an empty id list in a JPQL {@code IN} (it matches nothing). */
    private static final UUID NO_ID = new UUID(0L, 0L);

    /** Records several lines at once ("set all its options to 20"); each as {@link #recordCount} would. */
    @Transactional
    public StockCount recordCounts(UUID countId, Map<UUID, BigDecimal> countedByVariant, UUID staffUserId) {
        StockCount count = get(countId);
        for (Map.Entry<UUID, BigDecimal> e : countedByVariant.entrySet()) {
            count = recordCount(countId, e.getKey(), e.getValue(), null, staffUserId);
        }
        return count;
    }

    /** Records (or corrects) one line's counted quantity — any number of times while still DRAFT. */
    @Transactional
    public StockCount recordCount(UUID countId, UUID variantId, BigDecimal countedQty, String note, UUID staffUserId) {
        if (countedQty == null || countedQty.signum() < 0) {
            throw new IllegalArgumentException("Counted quantity cannot be negative");
        }
        StockCount count = get(countId);
        assertMutable(count);

        StockCountLine line = count.getLines().stream()
                .filter(l -> l.getVariant().getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(variantId + " isn't on this count"));

        QuantityRules.requireSensible(line.getVariant().getSku(), line.getVariant(), countedQty);
        line.setCountedQty(countedQty);
        line.setSkipped(false);
        line.setNote(note);
        line.setCountedByUserId(staffUserId);
        line.setCountedAt(Instant.now(clock));
        if (count.getStatus() == StockCountStatus.DRAFT) {
            count.setStatus(StockCountStatus.IN_PROGRESS);
        }
        return countRepository.save(count);
    }

    /**
     * "Skip" (V85): the counter looked and leaves this item as it was. It is not changed when the count is applied, but it
     * is no longer "still to count".
     */
    @Transactional
    public StockCount skip(UUID countId, UUID variantId, UUID staffUserId) {
        StockCount count = get(countId);
        assertMutable(count);
        StockCountLine line = count.getLines().stream()
                .filter(l -> l.getVariant().getId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(variantId + " isn't on this count"));
        line.setCountedQty(null);
        line.setSkipped(true);
        line.setCountedByUserId(staffUserId);
        line.setCountedAt(Instant.now(clock));
        if (count.getStatus() == StockCountStatus.DRAFT) {
            count.setStatus(StockCountStatus.IN_PROGRESS);
        }
        return countRepository.save(count);
    }

    /** Puts an option on a count that is still open (a product added while counting), counted already. */
    @Transactional
    public StockCount addLine(UUID countId, UUID variantId, BigDecimal countedQty, UUID staffUserId) {
        StockCount count = get(countId);
        assertMutable(count);
        if (count.getLines().stream().noneMatch(l -> l.getVariant().getId().equals(variantId))) {
            ProductVariant variant = variantRepository.findById(variantId)
                    .orElseThrow(() -> new NotFoundException("No such option: " + variantId));
            StockCountLine line = new StockCountLine();
            line.setStockCount(count);
            line.setVariant(variant);
            line.setSystemQtyAtStart(currentOnHand(variantId));
            count.getLines().add(line);
            countRepository.save(count);
        }
        return recordCount(countId, variantId, countedQty, null, staffUserId);
    }

    @Transactional
    public StockCount cancel(UUID countId) {
        StockCount count = get(countId);
        assertMutable(count);
        count.setStatus(StockCountStatus.CANCELLED);
        return countRepository.save(count);
    }

    /**
     * Applies every counted line, posting one STOCK_COUNT document for the net differences.
     * Locks every touched SHOP balance FIRST (same discipline as everywhere else in this
     * session), then checks ALL lines for drift against their frozen snapshot before deciding
     * or posting anything — a line with unresolved drift fails the WHOLE apply with a 409
     * naming exactly which SKUs need a resolution, rather than partially applying.
     */
    @Transactional
    public StockCount apply(UUID countId, Map<UUID, ConflictResolution> resolutionsByVariantId, UUID staffUserId) {
        StockCount count = get(countId);
        if (count.getStatus() == StockCountStatus.APPLIED || count.getStatus() == StockCountStatus.CANCELLED) {
            throw new UnprocessableException(count.getCountNo() + " is already " + count.getStatus());
        }

        List<StockCountLine> countedLines = count.getLines().stream()
                .filter(StockCountLine::isCounted)
                .sorted(Comparator.comparing(l -> l.getVariant().getId()))
                .toList();
        if (countedLines.isEmpty()) {
            throw new UnprocessableException("Nothing on " + count.getCountNo() + " has been counted yet");
        }

        Map<UUID, StockBalance> lockedBalances = new LinkedHashMap<>();
        for (StockCountLine line : countedLines) {
            UUID variantId = line.getVariant().getId();
            lockedBalances.put(variantId,
                    balanceRepository.lockByVariantIdAndAccount(variantId, StockAccount.SHOP).orElse(null));
        }

        Map<UUID, ConflictResolution> resolutions = resolutionsByVariantId != null ? resolutionsByVariantId : Map.of();
        List<String> unresolvedConflicts = new ArrayList<>();
        for (StockCountLine line : countedLines) {
            UUID variantId = line.getVariant().getId();
            BigDecimal currentOnHand = onHandOf(lockedBalances.get(variantId));
            boolean driftedSinceSnapshot = currentOnHand.compareTo(line.getSystemQtyAtStart()) != 0;
            if (driftedSinceSnapshot && !resolutions.containsKey(variantId)) {
                unresolvedConflicts.add(line.getVariant().getSku() + " (was " + line.getSystemQtyAtStart()
                        + " when counted, is now " + currentOnHand + ")");
            }
        }
        if (!unresolvedConflicts.isEmpty()) {
            throw new ConflictException(
                    "Stock changed since these lines were counted — resolve each one (keep the count, or keep "
                            + "the difference) and retry: " + String.join("; ", unresolvedConflicts));
        }

        StockDocument document = new StockDocument();
        document.setDocumentType(StockDocumentType.STOCK_COUNT);
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.STOCK_COUNT));
        document.setPostedByUserId(staffUserId);
        document.setReferenceType(COUNT_REFERENCE_TYPE);
        document.setReferenceId(count.getId());
        document.setNotes(count.getReason() != null ? count.getReason() : ("Stock count " + count.getCountNo()));

        int lineNo = 1;
        List<String> unknownCost = new ArrayList<>();
        for (StockCountLine line : countedLines) {
            UUID variantId = line.getVariant().getId();
            // counted in a stock take: a number the shop stands behind from now on (V78), and so no longer "not tracked"
            line.getVariant().setStockBasis(com.nbh.backend.catalog.entity.StockBasis.COUNTED);
            StockBalance balance = lockedBalances.get(variantId);
            BigDecimal currentOnHand = onHandOf(balance);

            BigDecimal target;
            if (resolutions.get(variantId) == ConflictResolution.KEEP_DIFFERENCE) {
                BigDecimal observedDiff = line.getCountedQty().subtract(line.getSystemQtyAtStart());
                target = currentOnHand.add(observedDiff);
            } else {
                target = line.getCountedQty();
            }

            BigDecimal delta = target.subtract(currentOnHand);
            if (delta.signum() == 0) {
                continue;
            }

            StockDocumentLine docLine = new StockDocumentLine();
            docLine.setDocument(document);
            docLine.setLineNo(lineNo++);
            docLine.setVariant(line.getVariant());
            docLine.setQty(delta.abs());
            if (delta.signum() > 0) {
                BigDecimal currentAvg = balance != null ? balance.getAvgCost() : null;
                BigDecimal resolvedCost = currentAvg != null && currentAvg.signum() > 0
                        ? currentAvg : line.getVariant().getStandardCost();
                if (resolvedCost == null) {
                    // A shop-floor count must go through even where no cost was ever entered (owner, 2026-10-26: one
                    // person counts and applies); it is valued at 0 and the document says so, as the stock list does.
                    resolvedCost = BigDecimal.ZERO;
                    unknownCost.add(line.getVariant().getSku());
                }
                docLine.setSourceAccount(StockAccount.ADJUSTMENT);
                docLine.setTargetAccount(StockAccount.SHOP);
                docLine.setUnitCost(resolvedCost);
            } else {
                docLine.setSourceAccount(StockAccount.SHOP);
                docLine.setTargetAccount(StockAccount.ADJUSTMENT);
            }
            document.getLines().add(docLine);
        }

        if (!unknownCost.isEmpty()) {
            String skus = unknownCost.size() <= 10 ? String.join(", ", unknownCost)
                    : String.join(", ", unknownCost.subList(0, 10)) + " and " + (unknownCost.size() - 10) + " more";
            String note = document.getNotes() + " (cost not known, valued at 0: " + skus + ")";
            document.setNotes(note.length() > 480 ? note.substring(0, 477) + "..." : note);
        }
        StockDocument posted = document.getLines().isEmpty() ? null : postingService.post(document);

        count.setStatus(StockCountStatus.APPLIED);
        count.setAppliedByUserId(staffUserId);
        count.setAppliedAt(Instant.now(clock));
        count.setAppliedDocument(posted);
        return countRepository.save(count);
    }

    /** Marker on the STOCK_COUNT document's referenceType, linking it back to the count that produced it. */
    public static final String COUNT_REFERENCE_TYPE = "STOCK_COUNT_SESSION";

    private void assertMutable(StockCount count) {
        if (count.getStatus() == StockCountStatus.APPLIED || count.getStatus() == StockCountStatus.CANCELLED) {
            throw new UnprocessableException(count.getCountNo() + " is " + count.getStatus() + " and can't be changed");
        }
    }

    private BigDecimal currentOnHand(UUID variantId) {
        return balanceRepository.findByVariantIdAndAccount(variantId, StockAccount.SHOP)
                .map(StockBalance::getOnHandQty)
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal onHandOf(StockBalance balance) {
        return balance != null ? balance.getOnHandQty() : BigDecimal.ZERO;
    }
}
