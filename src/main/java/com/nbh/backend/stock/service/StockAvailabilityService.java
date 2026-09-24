package com.nbh.backend.stock.service;

import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.entity.StockAccount;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single definition of "available": SHOP on-hand minus reserved. Every
 * reader of stock levels — the shop, add-to-cart, checkout, the admin product
 * editor and list, analytics — goes through this class, never
 * {@link StockBalanceRepository} directly. That is enforced by an ArchUnit
 * rule in {@code SingleWritePathArchitectureTest} ("no class outside
 * {@code com.nbh.backend.stock.service} may depend on
 * {@code StockBalanceRepository} or {@code StockReservationRepository}"), not
 * just a convention.
 *
 * <p>Before this class existed, three different call sites each computed
 * "in stock" slightly differently (the shop page summed every branch
 * including damaged-goods locations; add-to-cart only checked the main
 * branch's shelf) — a customer could be told something was available and then
 * refused at checkout. With branches removed and one formula in one place,
 * that class of bug can no longer happen.
 */
@Service
public class StockAvailabilityService {

    private final StockBalanceRepository balanceRepository;

    public StockAvailabilityService(StockBalanceRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
    }

    /** Sellable quantity for one variant (0 if it has never been received). */
    @Transactional(readOnly = true)
    public BigDecimal available(UUID variantId) {
        return available(java.util.List.of(variantId)).getOrDefault(variantId, BigDecimal.ZERO);
    }

    /** Sellable quantity per variant, batched — variants never received are simply absent from the map. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> available(Collection<UUID> variantIds) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        if (variantIds.isEmpty()) {
            return result;
        }
        for (StockBalanceRepository.VariantAvailability a : balanceRepository.availabilityForVariants(variantIds)) {
            result.put(a.getVariantId(), a.getAvailable());
        }
        return result;
    }

    /** The current moving-average cost for a variant's SHOP stock — used to snapshot a sale's cost at dispatch time. */
    @Transactional(readOnly = true)
    public BigDecimal avgCost(UUID variantId) {
        return balanceRepository.findByVariantIdAndAccount(variantId, StockAccount.SHOP)
                .map(com.nbh.backend.stock.entity.StockBalance::getAvgCost)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public Page<InventoryLineDto> browse(String q, boolean belowReorderOnly, Pageable pageable) {
        return balanceRepository.browseInventory(belowReorderOnly, q, pageable);
    }

    @Transactional(readOnly = true)
    public long countBelowReorderLevel() {
        return balanceRepository.countBelowReorderLevel();
    }
}
