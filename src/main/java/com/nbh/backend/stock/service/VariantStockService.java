package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * "We counted N on the shelf" for the product editor — the everyday case of a shop owner typing
 * a quantity, without knowing about receipts or adjustments. The difference from what's actually
 * on hand is posted through {@link StockPostingService} (the single write path), so the ledger
 * stays a complete, balanced audit trail. Both directions post as a single ADJUSTMENT document
 * against the virtual ADJUSTMENT account (not a phantom GOODS_RECEIPT for a gain) — a count
 * finding more stock than expected is a discrepancy to record, not a delivery that happened.
 *
 * <p><b>Phase 2 fix (2026-09-27), D6 — three real bugs in the previous version:</b>
 * <ol>
 *   <li>It targeted the SELLABLE quantity (on-hand minus reserved), not physical on-hand — so
 *   typing "50" with 5 units reserved against pending orders posted a receipt for 15, not 10,
 *   silently drifting on-hand away from what was actually counted on the shelf.</li>
 *   <li>The "current" quantity was read unlocked, then a document built and posted moments
 *   later — a concurrent sale or receipt in between meant the posted delta could already be
 *   wrong by the time it landed. Fixed by locking the SHOP balance FIRST and reading on-hand
 *   from that same locked row; {@code post()} below re-locks it, which is safe and cheap
 *   (Postgres row locks are reentrant within one transaction), not a second race window.</li>
 *   <li>A missing cost silently fell back to zero, corrupting the moving average for every
 *   later sale. Now resolved from the locked balance's own current average, then the variant's
 *   standard cost, and only fails if neither exists — matching this codebase's existing
 *   "don't post at zero when the real reason is a missing cost" stance elsewhere.</li>
 * </ol>
 * Branches/locations were removed 2026-09-24 (founder decision — see HANDOFF.md); there is one
 * shop, so no location lookup is needed.
 */
@Service
public class VariantStockService {

    public record Result(BigDecimal previousOnHand, BigDecimal newOnHand, String documentNo) {}

    private final ProductVariantRepository variantRepository;
    private final StockBalanceRepository balanceRepository;
    private final StockPostingService postingService;
    private final StockDocumentNoGenerator documentNoGenerator;

    public VariantStockService(ProductVariantRepository variantRepository,
                                StockBalanceRepository balanceRepository,
                                StockPostingService postingService,
                                StockDocumentNoGenerator documentNoGenerator) {
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.postingService = postingService;
        this.documentNoGenerator = documentNoGenerator;
    }

    /** {@link #setOnHand(UUID, UUID, BigDecimal, BigDecimal, String, boolean, StaffUser)} that refuses a gain whose cost is unknown. */
    @Transactional
    public Result setOnHand(UUID productId, UUID variantId, BigDecimal targetOnHand, BigDecimal unitCost,
                             String reason, StaffUser staff) {
        return setOnHand(productId, variantId, targetOnHand, unitCost, reason, false, staff);
    }

    /**
     * @param unitCost cost per unit for a gain; falls back to the current average, then the
     *                 variant's standard cost — required only when neither exists.
     * @param reason optional note for the resulting ADJUSTMENT document (e.g. "physical count").
     * @param allowUnknownCost what a gain does when no cost can be found anywhere: {@code false} refuses it (the
     *                 product editor's stance, see the class javadoc), {@code true} values it at zero and says
     *                 so in the document's note. The shop-floor stock list and the product card's quick edit send
     *                 {@code true}: a small shop that never entered costs must still be able to type a number.
     * @return the before/after on-hand figures, with a null document number when nothing needed to change.
     */
    @Transactional
    public Result setOnHand(UUID productId, UUID variantId, BigDecimal targetOnHand, BigDecimal unitCost,
                             String reason, boolean allowUnknownCost, StaffUser staff) {
        if (targetOnHand == null || targetOnHand.signum() < 0) {
            throw new IllegalArgumentException("Stock quantity cannot be negative");
        }
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId));
        if (!variant.getProduct().getId().equals(productId)) {
            throw new EntityNotFoundException("No variant " + variantId + " on product " + productId);
        }
        QuantityRules.requireSensible(variant.getSku(), variant, targetOnHand);
        if (variant.isKit()) {
            // A kit sells however many whole kits its parts can still make up; stock posted against the kit
            // itself would be a phantom the checkout never reads.
            throw new UnprocessableException(variant.getSku() + " is a kit: it holds no stock of its own, "
                    + "so change the stock of the products it is made from instead");
        }

        // Lock first, read from the locked row — see the class javadoc's point 2.
        StockBalance locked = balanceRepository.lockByVariantIdAndAccount(variantId, StockAccount.SHOP).orElse(null);
        BigDecimal currentOnHand = locked != null ? locked.getOnHandQty() : BigDecimal.ZERO;
        BigDecimal delta = targetOnHand.subtract(currentOnHand);
        if (delta.signum() == 0) {
            return new Result(currentOnHand, currentOnHand, null);
        }

        StockDocument document = new StockDocument();
        document.setDocumentType(StockDocumentType.ADJUSTMENT);
        document.setDocumentNo(documentNoGenerator.next(StockDocumentType.ADJUSTMENT));
        document.setPostedByUserId(staff.getId());
        String note = reason != null && !reason.isBlank() ? reason : "Stock count corrected from the product editor";

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setQty(delta.abs());

        if (delta.signum() > 0) {
            BigDecimal resolvedCost = unitCost;
            if (resolvedCost == null) {
                BigDecimal currentAvg = locked != null ? locked.getAvgCost() : null;
                resolvedCost = currentAvg != null && currentAvg.signum() > 0 ? currentAvg : variant.getStandardCost();
            }
            if (resolvedCost == null) {
                if (!allowUnknownCost) {
                    throw new UnprocessableException(
                            variant.getSku() + ": a unit cost is required — this variant has no current average "
                                    + "cost or standard cost to fall back on");
                }
                resolvedCost = BigDecimal.ZERO;
                note = note + " (cost not known, valued at 0)";
            }
            line.setSourceAccount(StockAccount.ADJUSTMENT);
            line.setTargetAccount(StockAccount.SHOP);
            line.setUnitCost(resolvedCost);
        } else {
            line.setSourceAccount(StockAccount.SHOP);
            line.setTargetAccount(StockAccount.ADJUSTMENT);
        }
        document.setNotes(note);
        document.getLines().add(line);

        StockDocument posted = postingService.post(document);
        return new Result(currentOnHand, targetOnHand, posted.getDocumentNo());
    }
}
