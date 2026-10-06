package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.kits.dto.KitDtos;
import com.nbh.backend.kits.entity.Kit;
import com.nbh.backend.kits.entity.KitComponent;
import com.nbh.backend.kits.repository.KitComponentRepository;
import com.nbh.backend.kits.repository.KitRepository;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * How many whole kits the shelf can make up right now. A kit's own SKU holds no stock; what it can
 * sell is limited by whichever component runs out first. Components that are no longer purchasable are
 * left out, exactly as checkout leaves them out ({@code KitService#componentsForVariant}), so the shop
 * and the checkout always agree about whether a kit can be bought.
 *
 * <p>Before this existed the catalogue marked every kit "in stock", and a shopper could put a kit whose
 * components were all sold out into a cart and only learn at the last step of checkout that it was gone.
 */
@Service
public class KitAvailabilityService {

    private final KitRepository kits;
    private final KitComponentRepository componentRepository;
    private final StockAvailabilityService stockAvailability;

    public KitAvailabilityService(KitRepository kits, KitComponentRepository componentRepository,
                                  StockAvailabilityService stockAvailability) {
        this.kits = kits;
        this.componentRepository = componentRepository;
        this.stockAvailability = stockAvailability;
    }

    /** One real component of a kit: the variant that holds the stock and how many of it one kit uses. */
    public record Part(ProductVariant variant, BigDecimal qty) {}

    /**
     * The purchasable parts of each kit, keyed by the kit's own variant id: exactly the set checkout reserves
     * (see {@code KitService#componentsForVariant}). A kit with none left maps to an empty list; a variant that
     * is not a kit is absent. The cart uses it to share one shelf between the lines that draw on it.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<Part>> partsOf(Collection<UUID> kitVariantIds) {
        if (kitVariantIds.isEmpty()) {
            return Map.of();
        }
        List<Kit> found = kits.findByVariantIdIn(kitVariantIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<KitComponent>> componentsByKit = componentRepository
                .findByKitIdInOrderByLineNoAsc(found.stream().map(Kit::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(c -> c.getKit().getId()));
        Map<UUID, List<Part>> result = new HashMap<>();
        for (Kit kit : found) {
            result.put(kit.getVariant().getId(), purchasable(componentsByKit.get(kit.getId())).stream()
                    .map(c -> new Part(c.getComponentVariant(), c.getQty()))
                    .toList());
        }
        return result;
    }

    /** Whole kits available, keyed by the kit's own variant id. A variant that is not a kit is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> wholeKitsAvailable(Collection<UUID> kitVariantIds) {
        if (kitVariantIds.isEmpty()) {
            return Map.of();
        }
        List<Kit> found = kits.findByVariantIdIn(kitVariantIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        List<UUID> kitIds = found.stream().map(Kit::getId).toList();
        Map<UUID, List<KitComponent>> componentsByKit = componentRepository.findByKitIdInOrderByLineNoAsc(kitIds)
                .stream()
                .collect(Collectors.groupingBy(c -> c.getKit().getId()));
        return wholeKitsAvailable(found, componentsByKit);
    }

    /** Same figure for kits whose components the caller has already loaded (no extra queries for them). */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> wholeKitsAvailable(Collection<Kit> kitList,
                                                     Map<UUID, List<KitComponent>> componentsByKit) {
        Set<UUID> componentVariantIds = new HashSet<>();
        for (Kit kit : kitList) {
            for (KitComponent c : purchasable(componentsByKit.get(kit.getId()))) {
                componentVariantIds.add(c.getComponentVariant().getId());
            }
        }
        Map<UUID, BigDecimal> onShelf = stockAvailability.available(componentVariantIds);

        Map<UUID, BigDecimal> result = new HashMap<>();
        for (Kit kit : kitList) {
            List<KitComponent> comps = purchasable(componentsByKit.get(kit.getId()));
            BigDecimal whole = null;
            for (KitComponent c : comps) {
                BigDecimal have = onShelf.getOrDefault(c.getComponentVariant().getId(), BigDecimal.ZERO);
                BigDecimal kitsFromThis = have.signum() <= 0
                        ? BigDecimal.ZERO
                        : have.divide(c.getQty(), 0, RoundingMode.DOWN);
                whole = whole == null ? kitsFromThis : whole.min(kitsFromThis);
            }
            result.put(kit.getVariant().getId(), whole == null ? BigDecimal.ZERO : whole);
        }
        return result;
    }

    private static List<KitComponent> purchasable(List<KitComponent> components) {
        if (components == null) {
            return List.of();
        }
        return components.stream().filter(c -> KitDtos.isComponentAvailable(c.getComponentVariant())).toList();
    }
}
