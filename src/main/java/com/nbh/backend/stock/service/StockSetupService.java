package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.StockBasis;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.shop.ShopSettings;
import com.nbh.backend.shop.ShopSettingsService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Taking the shop live before every shelf is counted (owner, 2026-10-26), the "Set many at once" part of the Stock tab:
 * <ul>
 *   <li>a number for many options at once (the whole shop, some categories or some products), marked ESTIMATED: by default
 *   only for options with no number yet, or overwriting every option when asked; each goes through the same "set stock"
 *   path as the stock list (one adjustment per option, unknown costs valued at 0 and said so);</li>
 *   <li>the "sell without stock" switches on the shop, a category or a product (see {@link StockTrackingService});</li>
 *   <li>a summary of where the shop stands: how many options are counted, estimated, without a number, and not tracked.</li>
 * </ul>
 */
@Service
public class StockSetupService {

    /** Stands in for an empty id list in a JPQL {@code IN} (it matches nothing). */
    private static final UUID NO_ID = new UUID(0L, 0L);

    public record Summary(boolean sellWithoutStockEverywhere, long counted, long estimated, long withoutNumber,
                          long notTracked, List<CategorySwitch> categories) {}

    public record CategorySwitch(UUID id, String name, UUID parentId, boolean sellWithoutStock) {}

    /** {@code options} in the choice; {@code changed} were (or would be) given the number; {@code skipped} kept theirs. */
    public record BulkSetResult(int options, int products, int changed, int skipped, boolean preview) {}

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ShopSettingsService shopSettings;
    private final StockTrackingService tracking;
    private final VariantStockService variantStock;
    private final ApplicationEventPublisher events;

    public StockSetupService(ProductVariantRepository variantRepository, ProductRepository productRepository,
                             CategoryRepository categoryRepository, ShopSettingsService shopSettings,
                             StockTrackingService tracking, VariantStockService variantStock,
                             ApplicationEventPublisher events) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.shopSettings = shopSettings;
        this.tracking = tracking;
        this.variantStock = variantStock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<StockBasis, Long> byBasis = new EnumMap<>(StockBasis.class);
        for (Object[] row : variantRepository.countByStockBasis()) {
            byBasis.put((StockBasis) row[0], (Long) row[1]);
        }
        long notTracked = tracking.untracked(variantRepository.idsInScope(true, List.of(NO_ID), List.of(NO_ID))).size();
        List<CategorySwitch> categories = categoryRepository.findAll().stream()
                .filter(c -> !c.isMockData())
                .sorted(java.util.Comparator.comparingInt(Category::getSortOrder)
                        .thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new CategorySwitch(c.getId(), c.getName(), c.getParent() == null ? null : c.getParent().getId(),
                        c.isSellWithoutStock()))
                .toList();
        return new Summary(shopSettings.get().isSellWithoutStock(), byBasis.getOrDefault(StockBasis.COUNTED, 0L),
                byBasis.getOrDefault(StockBasis.ESTIMATED, 0L), byBasis.getOrDefault(StockBasis.NONE, 0L), notTracked,
                categories);
    }

    @Transactional
    public void sellWithoutStockEverywhere(boolean on) {
        ShopSettings settings = shopSettings.get();
        settings.setSellWithoutStock(on);
        tellScreens();
    }

    @Transactional
    public void sellWithoutStockInCategory(UUID categoryId, boolean on) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("No category: " + categoryId));
        category.setSellWithoutStock(on);
        tellScreens();
    }

    @Transactional
    public void sellWithoutStockForProduct(UUID productId, boolean on) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        product.setSellWithoutStock(on);
        tellScreens();
    }

    /**
     * Gives these options {@code qty} on hand, marked ESTIMATED. Without {@code overwrite} only options with no number yet
     * (stock basis NONE) are given it: a counted zero is a real "none on the shelf", never replaced by a guess. With
     * {@code preview} nothing changes and the result says what would.
     */
    @Transactional
    public BulkSetResult setMany(boolean everything, List<UUID> categoryIds, List<UUID> productIds, BigDecimal qty,
                                 boolean overwrite, boolean preview, StaffUser staff) {
        if (qty == null || qty.signum() < 0) {
            throw new IllegalArgumentException("Give a stock number of 0 or more");
        }
        List<UUID> cats = categoryIds == null || categoryIds.isEmpty() ? List.of(NO_ID) : categoryIds;
        List<UUID> prods = productIds == null || productIds.isEmpty() ? List.of(NO_ID) : productIds;
        if (!everything && cats.get(0).equals(NO_ID) && prods.get(0).equals(NO_ID)) {
            throw new IllegalArgumentException("Choose what to set: the whole shop, some categories or some products");
        }
        List<ProductVariant> options = variantRepository.findAllById(variantRepository.idsInScope(everything, cats, prods));
        Set<UUID> products = new HashSet<>();
        options.forEach(v -> products.add(v.getProduct().getId()));
        List<ProductVariant> targets = options.stream()
                .filter(v -> overwrite || v.getStockBasis() == StockBasis.NONE)
                .sorted(java.util.Comparator.comparing(ProductVariant::getId)) // the same lock order as everywhere else
                .toList();
        if (!preview) {
            for (ProductVariant v : targets) {
                variantStock.setOnHand(v.getProduct().getId(), v.getId(), qty, null,
                        "Set many at once: " + qty.stripTrailingZeros().toPlainString() + " (estimated)", true, staff);
                v.setStockBasis(StockBasis.ESTIMATED);
            }
            if (!targets.isEmpty()) {
                tellScreens();
            }
        }
        return new BulkSetResult(options.size(), products.size(), targets.size(), options.size() - targets.size(), preview);
    }

    private void tellScreens() {
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.STOCK));
    }
}
