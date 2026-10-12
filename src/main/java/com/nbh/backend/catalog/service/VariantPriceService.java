package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.ProductVariantDto;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Changes the price of several of a product's variants at once (VARIANTS_PLAN.md, phase 4 editor): staff pick them by
 * colour and/or size on the screen ("all Gold Shimmer", "all 100ml") and either set one price, add or take off an amount,
 * or change it by a percentage. All or nothing: one bad result refuses the whole change.
 */
@Service
public class VariantPriceService {

    /** Exactly one of {@code setPrice}, {@code adjustBy} (+/- shillings) or {@code adjustPercent} (+/- %). */
    public record Change(List<UUID> variantIds, BigDecimal setPrice, BigDecimal adjustBy, BigDecimal adjustPercent) {}

    /** The variants as saved, and what else changed because of the new prices (a sale or wholesale tier cleared). */
    public record Result(List<ProductVariantDto> variants, List<String> warnings) {}

    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    private final ProductRepository products;
    private final ProductVariantRepository variants;

    public VariantPriceService(ProductRepository products, ProductVariantRepository variants) {
        this.products = products;
        this.variants = variants;
    }

    @Transactional
    public Result apply(UUID productId, Change change) {
        products.findById(productId).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No such product."));
        if (change == null || change.variantIds() == null || change.variantIds().isEmpty()) {
            throw new IllegalArgumentException("Pick at least one variant.");
        }
        int given = (change.setPrice() != null ? 1 : 0) + (change.adjustBy() != null ? 1 : 0)
                + (change.adjustPercent() != null ? 1 : 0);
        if (given != 1) {
            throw new IllegalArgumentException("Give one change: a new price, an amount to add or take off, or a percentage.");
        }
        Map<UUID, ProductVariant> live = variants.findByProductIdAndDeletedFalseOrderBySortOrderAsc(productId).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        if (!live.keySet().containsAll(new HashSet<>(change.variantIds()))) {
            throw new IllegalArgumentException("Some of those variants are not on this product any more. Reload and try again.");
        }
        List<ProductVariant> picked = change.variantIds().stream().distinct().map(live::get).toList();
        List<String> warnings = new java.util.ArrayList<>();
        for (ProductVariant v : picked) {
            BigDecimal next = next(v, change);
            if (next.signum() <= 0) {
                throw new IllegalArgumentException(label(v) + " would cost " + next.toPlainString()
                        + ". A price must be more than 0.");
            }
            if (next.compareTo(MAX_PRICE) > 0) {
                throw new IllegalArgumentException(label(v) + " would cost " + next.toPlainString() + ", which is too much.");
            }
            v.setSellingPrice(next);
            // Same rule as the product list's bulk price change: a sale or wholesale price must stay below the price.
            if (v.getDealPrice() != null && v.getDealPrice().compareTo(next) >= 0) {
                v.setDealPrice(null);
                v.setDealEndsAt(null);
                warnings.add(label(v) + ": its sale price was no longer below the new price, so the sale was cleared");
            }
            if (v.getWholesalePrice() != null && v.getWholesalePrice().compareTo(next) >= 0) {
                v.setWholesaleMinQty(null);
                v.setWholesalePrice(null);
                warnings.add(label(v) + ": its wholesale price was no longer below the new price, so the wholesale tier was cleared");
            }
        }
        variants.saveAll(picked);
        products.touch(productId, java.time.Instant.now());
        return new Result(picked.stream().map(ProductVariantDto::from).toList(), warnings);
    }

    /** The new price, to the shilling for a percentage (shops price in whole shillings). */
    private static BigDecimal next(ProductVariant v, Change c) {
        if (c.setPrice() != null) {
            return c.setPrice().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal current = v.getSellingPrice();
        if (current == null) {
            throw new IllegalArgumentException(label(v) + " has no price yet, so it can't be raised or lowered. Set a price instead.");
        }
        if (c.adjustBy() != null) {
            return current.add(c.adjustBy()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(c.adjustPercent().movePointLeft(2));
        return current.multiply(factor).setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String label(ProductVariant v) {
        String option = v.shortOption();
        return option != null ? option : v.getSku();
    }
}
