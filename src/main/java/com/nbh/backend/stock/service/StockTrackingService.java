package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.StockBasis;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.shop.ShopSettings;
import com.nbh.backend.shop.ShopSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which options are "not tracked yet" (owner, 2026-10-26): no stock number yet (stock basis NONE) and covered by a "sell
 * without stock" switch on the shop, a category or the product. Such an option can be ordered without a stock limit: the
 * availability everyone reads ({@link StockAvailabilityService#available}) reports {@link #UNTRACKED_AVAILABLE} for it,
 * checkout holds nothing for it (so dispatch, cancelling and returns move no stock either: they only act on held lines),
 * and the orders themselves are the record of what went out. A number for it (typed, received, estimated in bulk or counted
 * in a stock take) makes it tracked like any other.
 */
@Service
public class StockTrackingService {

    /** What an option that is not tracked yet reports as available: plenty, without being a number anyone counted. */
    public static final BigDecimal UNTRACKED_AVAILABLE = new BigDecimal("9999");

    private final ProductVariantRepository variantRepository;
    private final ShopSettingsRepository shopSettingsRepository;

    public StockTrackingService(ProductVariantRepository variantRepository, ShopSettingsRepository shopSettingsRepository) {
        this.variantRepository = variantRepository;
        this.shopSettingsRepository = shopSettingsRepository;
    }

    /** Of these options, the ones that are not tracked yet. */
    @Transactional(readOnly = true)
    public Set<UUID> untracked(Collection<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(variantRepository.untrackedAmong(variantIds, StockBasis.NONE, sellingWithoutStockEverywhere()));
    }

    @Transactional(readOnly = true)
    public boolean sellingWithoutStockEverywhere() {
        return shopSettingsRepository.findFirstByOrderByCreatedAtAsc().map(ShopSettings::isSellWithoutStock).orElse(false);
    }
}
