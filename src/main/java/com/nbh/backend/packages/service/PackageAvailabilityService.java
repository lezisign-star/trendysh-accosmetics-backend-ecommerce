package com.nbh.backend.packages.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.packages.dto.PackageDtos;
import com.nbh.backend.packages.entity.ProductPackage;
import com.nbh.backend.packages.entity.PackageComponent;
import com.nbh.backend.packages.repository.PackageComponentRepository;
import com.nbh.backend.packages.repository.PackageRepository;
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
 * How many whole packages the shelf can make up right now. A package's own SKU holds no stock; what it can
 * sell is limited by whichever component runs out first. Components that are no longer purchasable are
 * left out, exactly as checkout leaves them out ({@code PackageService#componentsForVariant}), so the shop
 * and the checkout always agree about whether a package can be bought.
 *
 * <p>Before this existed the catalogue marked every package "in stock", and a shopper could put a package whose
 * components were all sold out into a cart and only learn at the last step of checkout that it was gone.
 */
@Service
public class PackageAvailabilityService {

    private final PackageRepository packages;
    private final PackageComponentRepository componentRepository;
    private final StockAvailabilityService stockAvailability;

    public PackageAvailabilityService(PackageRepository packages, PackageComponentRepository componentRepository,
                                  StockAvailabilityService stockAvailability) {
        this.packages = packages;
        this.componentRepository = componentRepository;
        this.stockAvailability = stockAvailability;
    }

    /** One real component of a package: the variant that holds the stock and how many of it one package uses. */
    public record Part(ProductVariant variant, BigDecimal qty) {}

    /**
     * The purchasable parts of each package, keyed by the package's own variant id: exactly the set checkout reserves
     * (see {@code PackageService#componentsForVariant}). A package with none left maps to an empty list; a variant that
     * is not a package is absent. The cart uses it to share one shelf between the lines that draw on it.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<Part>> partsOf(Collection<UUID> packageVariantIds) {
        if (packageVariantIds.isEmpty()) {
            return Map.of();
        }
        List<ProductPackage> found = packages.findByVariantIdIn(packageVariantIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PackageComponent>> componentsByPackage = componentRepository
                .findByProductPackageIdInOrderByLineNoAsc(found.stream().map(ProductPackage::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(c -> c.getProductPackage().getId()));
        Map<UUID, List<Part>> result = new HashMap<>();
        for (ProductPackage pkg : found) {
            result.put(pkg.getVariant().getId(), purchasable(componentsByPackage.get(pkg.getId())).stream()
                    .map(c -> new Part(c.getComponentVariant(), c.getQty()))
                    .toList());
        }
        return result;
    }

    /**
     * Whole packages the shelf can make up from exactly these parts: a basket line whose shopper swapped some of the
     * package's products for another colour or size draws on the picked variants' shelves, not the defaults'.
     */
    @Transactional(readOnly = true)
    public BigDecimal wholePackagesFrom(List<Part> parts) {
        if (parts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<UUID, BigDecimal> onShelf = stockAvailability.available(
                parts.stream().map(p -> p.variant().getId()).distinct().toList());
        return wholePackages(parts, onShelf::get);
    }

    /**
     * How many whole packages these parts make from the shelf figures given. Parts that draw on the same variant share its
     * shelf (two products of a package can be picked to the same colour, or a package can hold two shades of one product that a
     * pick makes the same), so what one package uses of it is their quantities added up: counting each part against the whole
     * shelf said "in stock" for a package that checkout, which reserves part by part, then refused.
     *
     * @param shelf what is on the shelf for a variant; null counts as none
     */
    public static BigDecimal wholePackages(List<Part> parts, java.util.function.Function<UUID, BigDecimal> shelf) {
        Map<UUID, BigDecimal> perPackage = new java.util.LinkedHashMap<>();
        for (Part part : parts) {
            perPackage.merge(part.variant().getId(), part.qty(), BigDecimal::add);
        }
        BigDecimal whole = null;
        for (Map.Entry<UUID, BigDecimal> use : perPackage.entrySet()) {
            BigDecimal have = shelf.apply(use.getKey());
            BigDecimal packagesFromThis = have == null || have.signum() <= 0
                    ? BigDecimal.ZERO
                    : have.divide(use.getValue(), 0, RoundingMode.DOWN);
            whole = whole == null ? packagesFromThis : whole.min(packagesFromThis);
        }
        return whole == null ? BigDecimal.ZERO : whole;
    }

    /** Whole packages available, keyed by the package's own variant id. A variant that is not a package is absent. */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> wholePackagesAvailable(Collection<UUID> packageVariantIds) {
        if (packageVariantIds.isEmpty()) {
            return Map.of();
        }
        List<ProductPackage> found = packages.findByVariantIdIn(packageVariantIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        List<UUID> packageIds = found.stream().map(ProductPackage::getId).toList();
        Map<UUID, List<PackageComponent>> componentsByPackage = componentRepository.findByProductPackageIdInOrderByLineNoAsc(packageIds)
                .stream()
                .collect(Collectors.groupingBy(c -> c.getProductPackage().getId()));
        return wholePackagesAvailable(found, componentsByPackage);
    }

    /** Same figure for packages whose components the caller has already loaded (no extra queries for them). */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> wholePackagesAvailable(Collection<ProductPackage> packageList,
                                                     Map<UUID, List<PackageComponent>> componentsByPackage) {
        Set<UUID> componentVariantIds = new HashSet<>();
        for (ProductPackage pkg : packageList) {
            for (PackageComponent c : purchasable(componentsByPackage.get(pkg.getId()))) {
                componentVariantIds.add(c.getComponentVariant().getId());
            }
        }
        Map<UUID, BigDecimal> onShelf = stockAvailability.available(componentVariantIds);

        Map<UUID, BigDecimal> result = new HashMap<>();
        for (ProductPackage pkg : packageList) {
            List<Part> parts = purchasable(componentsByPackage.get(pkg.getId())).stream()
                    .map(c -> new Part(c.getComponentVariant(), c.getQty()))
                    .toList();
            result.put(pkg.getVariant().getId(), wholePackages(parts, onShelf::get));
        }
        return result;
    }

    private static List<PackageComponent> purchasable(List<PackageComponent> components) {
        if (components == null) {
            return List.of();
        }
        return components.stream().filter(c -> PackageDtos.isComponentAvailable(c.getComponentVariant())).toList();
    }
}
