package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.QuickEditDtos.Edit;
import com.nbh.backend.catalog.dto.QuickEditDtos.Row;
import com.nbh.backend.catalog.dto.QuickEditDtos.SaveResult;
import com.nbh.backend.catalog.dto.QuickEditDtos.SaveRow;
import com.nbh.backend.catalog.dto.QuickEditDtos.WholesaleEdit;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.insights.ReorderLevelService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.stock.service.StockAvailabilityService.Shelf;
import com.nbh.backend.stock.service.VariantStockService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The quick-edit table (owner, 2026-10-31): every option's price, wholesale tier, reorder level and shelf count in one
 * list, changed without opening each product. It adds no rules of its own about what a price or a count may be: each
 * change goes through the service that already owns it — {@link VariantPriceService} for the price (which clears a sale
 * or wholesale price no longer below it), {@link WholesalePricingService} for the tier, {@link ReorderLevelService}, and
 * {@link VariantStockService} for the count (the stock ledger's single write path). What it adds is a row saved as one:
 * every number on the row or none, and nothing written over a change someone else made since the list was loaded.
 */
@Service
public class QuickEditService {

    /** The most rows one page may ask for. */
    static final int MAX_PAGE_SIZE = 200;

    private final ProductVariantRepository variants;
    private final StockAvailabilityService availability;
    private final VariantPriceService prices;
    private final WholesalePricingService wholesale;
    private final ReorderLevelService reorderLevels;
    private final VariantStockService stock;

    public QuickEditService(ProductVariantRepository variants, StockAvailabilityService availability,
                            VariantPriceService prices, WholesalePricingService wholesale,
                            ReorderLevelService reorderLevels, VariantStockService stock) {
        this.variants = variants;
        this.availability = availability;
        this.prices = prices;
        this.wholesale = wholesale;
        this.reorderLevels = reorderLevels;
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public Page<Row> rows(String q, UUID categoryId, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        // the query has its own order (by product, then the product's option order), so the page carries none
        Pageable page = PageRequest.of(pageable.getPageNumber(), Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE));
        Page<ProductVariant> found = variants.findForQuickEdit(query, categoryId, page);
        Map<UUID, Shelf> shelf = availability.shelf(found.map(ProductVariant::getId).getContent());
        return found.map(v -> row(v, shelf.get(v.getId())));
    }

    /**
     * Saves one row: all of it or none of it. Prices need {@code canPrice} (PRODUCT_MANAGE), the count and reorder level
     * {@code canStock} (STOCK_ADJUST), as on their own screens. Every "as shown" value is checked before anything is
     * written, so a row someone else changed meanwhile is refused whole instead of half saved.
     */
    @Transactional
    public SaveResult save(UUID variantId, SaveRow in, boolean canPrice, boolean canStock, StaffUser staff) {
        if (in == null) throw new IllegalArgumentException("Nothing to save.");
        ProductVariant v = variants.findWithProductById(variantId)
                .filter(x -> !x.isDeleted() && !x.getProduct().isDeleted())
                .orElseThrow(() -> new NotFoundException("This option is not in the shop any more. Reload the list."));
        if (v.isPackage()) {
            throw new UnprocessableException("This is a package, so it is changed under Packages, not here.");
        }
        boolean pricing = in.sellingPrice() != null || in.wholesale() != null;
        boolean stocking = in.reorderLevel() != null || in.onHand() != null;
        if (!pricing && !stocking) throw new IllegalArgumentException("Nothing to save.");
        if (pricing && !canPrice) {
            throw new AccessDeniedException("Only someone who manages products can change prices.");
        }
        if (stocking && !canStock) {
            throw new AccessDeniedException("Only someone who looks after stock can change stock or reorder levels.");
        }

        if (in.sellingPrice() != null) expectUnchanged("price", v.getSellingPrice(), in.sellingPrice().from());
        WholesaleEdit w = in.wholesale();
        if (w != null) {
            expectUnchanged("wholesale price", v.getWholesalePrice(), w.fromPrice());
            expectUnchanged("wholesale minimum order", number(v.getWholesaleMinQty()), number(w.fromMinQty()));
            if ((w.toPrice() == null) != (w.toMinQty() == null)) {
                throw new IllegalArgumentException(
                        "Give both the wholesale price and the minimum order, or clear both for no wholesale price.");
            }
        }
        if (in.reorderLevel() != null) expectUnchanged("reorder level", v.getReorderLevel(), in.reorderLevel().from());

        UUID productId = v.getProduct().getId();
        List<String> warnings = new ArrayList<>();

        // A new tier is checked against the new price, so the old one is taken off first: otherwise lowering both
        // together would report the old tier "cleared" by the new price just before the new tier is set.
        if (w != null) wholesale.clearWholesalePrice(productId, variantId);
        Edit price = in.sellingPrice();
        if (price != null && !same(v.getSellingPrice(), price.to())) {
            if (price.to() == null) {
                throw new IllegalArgumentException("Type a price. To stop selling this option, hide it in the product editor.");
            }
            BigDecimal shared = sharedPrice(v.getProduct());
            if (shared != null) {
                throw new UnprocessableException("Every option of " + v.getProduct().getName() + " costs the same (KSh "
                        + plain(shared) + "). Change that price on the product, not on one option.");
            }
            warnings.addAll(prices.apply(productId,
                    new VariantPriceService.Change(List.of(variantId), price.to(), null, null)).warnings());
        }
        if (w != null && w.toPrice() != null) {
            wholesale.setWholesalePrice(productId, variantId, w.toMinQty(), w.toPrice());
        }

        Edit level = in.reorderLevel();
        if (level != null && !same(v.getReorderLevel(), level.to())) {
            reorderLevels.set(variantId, level.to());
        }

        // The count last, through the stock ledger like every other count; it is the one change that posts a document.
        String documentNo = null;
        if (in.onHand() != null) {
            String reason = in.reason() != null && !in.reason().isBlank() ? in.reason().trim() : "Quick edit";
            documentNo = stock.setOnHand(productId, variantId, in.onHand(), null, reason, true, staff).documentNo();
        }

        return new SaveResult(row(v, availability.shelf(List.of(variantId)).get(variantId)), warnings, documentNo);
    }

    private static Row row(ProductVariant v, Shelf shelf) {
        Product p = v.getProduct();
        boolean dealRunning = v.getDealPrice() != null && v.getDealEndsAt() != null && v.getDealEndsAt().isAfter(Instant.now());
        Shelf s = shelf != null ? shelf : new Shelf(BigDecimal.ZERO, BigDecimal.ZERO, false);
        return new Row(v.getId(), p.getId(), p.getName(), v.shortOption(), v.getSku(), categoryName(v),
                v.isActive(), p.isPublished(),
                v.getSellingPrice(), v.getWholesalePrice(), v.getWholesaleMinQty(), v.getReorderLevel(),
                dealRunning ? v.getDealPrice() : null, dealRunning ? v.getDealEndsAt() : null,
                s.onHand(), s.reserved(), s.untracked(), sharedPrice(p));
    }

    /** The option's own category where its range lets categories differ, else the product's first (its main one). */
    private static String categoryName(ProductVariant v) {
        if (v.getProduct().isCategoryVaries() && v.getCategory() != null) return v.getCategory().getName();
        var categories = v.getProduct().getCategories();
        return categories == null || categories.isEmpty() ? null : categories.get(0).getName();
    }

    /** The one price a confirmed range gives all its options, which {@code RangeService.enforce} writes back over any other. */
    private static BigDecimal sharedPrice(Product p) {
        return p.getRangeReviewedAt() != null && !p.isPriceVaries() ? p.getSharedPrice() : null;
    }

    private static void expectUnchanged(String what, BigDecimal now, BigDecimal shown) {
        if (!same(now, shown)) {
            throw new ConflictException("Someone changed the " + what + " since you loaded this list (it is now "
                    + (now == null ? "blank" : plain(now)) + "). Reload to see it, then type your change again.");
        }
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal number(Integer n) {
        return n == null ? null : BigDecimal.valueOf(n);
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }
}
