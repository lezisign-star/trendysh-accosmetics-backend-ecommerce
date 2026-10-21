package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.*;
import com.nbh.backend.catalog.entity.*;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.kits.service.KitAvailabilityService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The public storefront read side of the catalogue — deliberately separate
 * from {@link ProductService}, which is the staff-authenticated write side.
 * Every method here only ever returns published, non-deleted products with
 * sellable (not deleted, active, priced — see {@link ProductVariant#isSellable()})
 * variants: a product mid-edit, or a variant an admin just hid or removed,
 * must never leak onto the storefront.
 */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;
    private final ProductReviewRepository reviewRepository;
    private final ProductSaleEventRepository saleEventRepository;
    private final ProductViewEventRepository viewEventRepository;
    private final CategoryRepository categoryRepository;
    private final StockAvailabilityService availabilityService;
    private final KitAvailabilityService kitAvailability;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaignService;
    private final ProductOptionValueRepository optionRepository;
    private final Clock clock;

    public CatalogService(ProductRepository productRepository,
                           ProductVariantRepository variantRepository,
                           ProductImageRepository imageRepository,
                           ProductReviewRepository reviewRepository,
                           ProductSaleEventRepository saleEventRepository,
                           ProductViewEventRepository viewEventRepository,
                           CategoryRepository categoryRepository,
                           StockAvailabilityService availabilityService,
                           KitAvailabilityService kitAvailability,
                           EffectivePriceResolver priceResolver,
                           PromoCampaignService promoCampaignService,
                           ProductOptionValueRepository optionRepository,
                           Clock clock) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
        this.reviewRepository = reviewRepository;
        this.saleEventRepository = saleEventRepository;
        this.viewEventRepository = viewEventRepository;
        this.categoryRepository = categoryRepository;
        this.availabilityService = availabilityService;
        this.kitAvailability = kitAvailability;
        this.priceResolver = priceResolver;
        this.promoCampaignService = promoCampaignService;
        this.optionRepository = optionRepository;
        this.clock = clock;
    }

    /** The storefront's browse grid, one card per sellable variant (Phase 3, 2026-09-24). */
    @Transactional(readOnly = true)
    public Page<CatalogVariantCardDto> browseCards(String q, String categorySlug, Boolean deal, Boolean popular,
                                                     Boolean newArrival, Pageable pageable) {
        // Kits have their own pages and are not listed among products; a search still finds them, as kit cards.
        boolean includeKits = blankToNull(q) != null;
        Page<ProductVariant> page = variantRepository.searchSellable(blankToNull(q), blankToNull(categorySlug),
                deal, popular, newArrival, includeKits, pageable);
        List<ProductVariant> pageVariants = page.getContent();
        if (pageVariants.isEmpty()) {
            return Page.empty(pageable);
        }

        List<UUID> productIds = pageVariants.stream().map(v -> v.getProduct().getId()).distinct().toList();
        List<UUID> variantIds = pageVariants.stream().map(ProductVariant::getId).toList();

        Map<UUID, Long> sellableCountByProduct = sellableVariantCountByProduct(productIds);
        Map<UUID, Cover> imageByVariant = imageByVariant(pageVariants);
        Map<UUID, ProductReviewRepository.RatingSummary> ratingByProduct = ratingSummaries(productIds);
        Map<UUID, BigDecimal> soldByVariant = soldSummariesByVariant(variantIds);
        Map<UUID, BigDecimal> availableByVariant = availability(pageVariants);
        PromoCampaign activeCampaign = promoCampaignService.getActiveOrNull();

        return page.map(v -> toCard(v, activeCampaign, sellableCountByProduct, imageByVariant, ratingByProduct,
                soldByVariant, availableByVariant));
    }

    /** A single variant's own product-detail page — its price, its siblings, its own + the product's description. */
    @Transactional(readOnly = true)
    public CatalogVariantDetailDto variantDetail(UUID variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .filter(ProductVariant::isSellable)
                .orElseThrow(() -> new EntityNotFoundException("No sellable variant: " + variantId));
        Product product = variant.getProduct();
        if (!product.isPublished() || product.isDeleted()) {
            throw new EntityNotFoundException("No sellable variant: " + variantId);
        }

        List<ProductVariant> siblings = variantRepository
                .findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(product.getId());
        List<UUID> siblingIds = siblings.stream().map(ProductVariant::getId).toList();
        List<UUID> productIds = List.of(product.getId());

        Map<UUID, Long> sellableCountByProduct = Map.of(product.getId(), (long) siblings.size());
        Map<UUID, Cover> imageByVariant = imageByVariant(siblings);
        Map<UUID, ProductReviewRepository.RatingSummary> ratingByProduct = ratingSummaries(productIds);
        Map<UUID, BigDecimal> soldByVariant = soldSummariesByVariant(siblingIds);
        Map<UUID, BigDecimal> availableByVariant = availability(siblings);
        PromoCampaign activeCampaign = promoCampaignService.getActiveOrNull();

        List<CatalogVariantCardDto> siblingCards = siblings.stream()
                .map(v -> toCard(v, activeCampaign, sellableCountByProduct, imageByVariant, ratingByProduct,
                        soldByVariant, availableByVariant))
                .toList();
        CatalogVariantCardDto card = siblingCards.stream()
                .filter(c -> c.variantId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("No sellable variant: " + variantId));

        // Only this option's own photos: never a sibling's, never a shared "whole product" one (owner, 2026-10-07).
        // The way to see a sibling's photos is to open the sibling. See PhotoOwnership.
        boolean singleOption = variantRepository.findByProductIdAndDeletedFalseOrderBySortOrderAsc(product.getId()).size() <= 1;
        List<CatalogImageDto> images = PhotoOwnership.galleryFor(variantId, singleOption,
                        imageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).stream()
                .map(CatalogImageDto::from)
                .toList();

        Category primary = product.getCategories().isEmpty() ? null : product.getCategories().get(0);
        Category primaryParent = primary != null ? primary.getParent() : null;

        return new CatalogVariantDetailDto(card, variant.getDescription(), product.getDescription(),
                product.getIngredients(), product.getHowToUse(), images, siblingCards,
                primary != null ? primary.getId() : null, primary != null ? primary.getName() : null,
                primary != null ? primary.getSlug() : null,
                primaryParent != null ? primaryParent.getId() : null,
                primaryParent != null ? primaryParent.getName() : null,
                primaryParent != null ? primaryParent.getSlug() : null,
                com.nbh.backend.imagecleanup.DescriptionCaps.splitHighlights(product.getHighlights()),
                product.getSeoTitle(), product.getSeoDescription(),
                pickerOptions(product.getId(), siblings, ProductOptionValue.Kind.COLOR),
                pickerOptions(product.getId(), siblings, ProductOptionValue.Kind.SIZE),
                ProductOptionsService.clean(variant.getOptionSize()) == null && product.getRangeReviewedAt() != null
                        ? ProductOptionsService.clean(product.getSharedSize()) : null);
    }

    /**
     * The picker's colours or sizes, in the product's stored order (V63), keeping only those a sellable variant has, so
     * the shop never offers a colour that cannot be bought. A product with no stored list (made before V63 and not
     * saved since) gets them from its sellable variants in display order.
     */
    private List<CatalogOptionDto> pickerOptions(UUID productId, List<ProductVariant> sellable, ProductOptionValue.Kind kind) {
        boolean color = kind == ProductOptionValue.Kind.COLOR;
        java.util.function.Function<ProductVariant, String> valueOf = color ? ProductVariant::getOptionColor
                : ProductVariant::getOptionSize;
        Map<String, ProductVariant> firstWith = new java.util.LinkedHashMap<>();
        for (ProductVariant v : sellable) {
            String value = ProductOptionsService.clean(valueOf.apply(v));
            if (value != null) {
                firstWith.putIfAbsent(value.toLowerCase(java.util.Locale.ROOT), v);
            }
        }
        if (firstWith.isEmpty()) {
            return List.of();
        }
        List<ProductOptionValue> stored = optionRepository.findByProductIdOrderByKindAscPositionAsc(productId).stream()
                .filter(o -> o.getKind() == kind)
                .filter(o -> firstWith.containsKey(o.getValue().trim().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        List<CatalogOptionDto> out = new java.util.ArrayList<>();
        if (!stored.isEmpty()) {
            for (ProductOptionValue o : stored) {
                ColorFamily family = color ? o.effectiveColorFamily() : null;
                out.add(new CatalogOptionDto(o.getValue(), color ? o.getSwatchHex() : null,
                        family == null ? null : family.name(), out.size()));
            }
            return out;
        }
        for (ProductVariant v : firstWith.values()) {
            ColorFamily family = color ? v.effectiveColorFamily() : null;
            out.add(new CatalogOptionDto(ProductOptionsService.clean(valueOf.apply(v)), color ? v.getSwatchHex() : null,
                    family == null ? null : family.name(), out.size()));
        }
        return out;
    }

    private CatalogVariantCardDto toCard(ProductVariant v, PromoCampaign activeCampaign,
                                          Map<UUID, Long> sellableCountByProduct, Map<UUID, Cover> imageByVariant,
                                          Map<UUID, ProductReviewRepository.RatingSummary> ratingByProduct,
                                          Map<UUID, BigDecimal> soldByVariant, Map<UUID, BigDecimal> availableByVariant) {
        Product p = v.getProduct();
        long sellableCount = sellableCountByProduct.getOrDefault(p.getId(), 1L);
        boolean hideName = sellableCount <= 1 || CatalogVariantCardDto.isMeaninglessName(v.getVariantName());
        String variantName = hideName ? null : v.getVariantName();
        String optionLabel = hideName ? null : v.shortOption();
        String displayName = optionLabel != null ? p.getName() + " — " + optionLabel : p.getName();

        // No cart-quantity context on a card, so BigDecimal.ZERO can never qualify for a wholesale
        // tier here — the tier is advertised separately via wholesaleMinQty/wholesalePrice below,
        // never through this resolved price/onDeal.
        EffectivePriceResolver.EffectivePrice price = priceResolver.resolve(v, activeCampaign, BigDecimal.ZERO);
        ProductReviewRepository.RatingSummary rating = ratingByProduct.get(p.getId());
        // A variant filed under a category of its own (its range lets categories differ, V68) is listed there instead.
        List<UUID> categoryIds = v.getCategory() != null && p.isCategoryVaries() ? List.of(v.getCategory().getId())
                : p.getCategories().stream().map(Category::getId).toList();

        BigDecimal availableQty = availableByVariant.getOrDefault(v.getId(), BigDecimal.ZERO);
        Cover cover = imageByVariant.get(v.getId());
        return new CatalogVariantCardDto(
                v.getId(), p.getId(), p.getSlug(), p.getName(), variantName, displayName,
                cover == null ? null : cover.url(), categoryIds, categoryIds.isEmpty() ? null : categoryIds.get(0),
                price.effectivePrice(), price.originalPrice(), price.onDeal(), price.dealEndsAt(),
                availableQty.signum() > 0,
                p.isPopular(), p.isNewArrival(),
                rating != null ? rating.getAvgRating() : 0.0, rating != null ? rating.getReviewCount() : 0L,
                soldByVariant.getOrDefault(v.getId(), BigDecimal.ZERO),
                availableQty, v.getSku(), p.getDescription(),
                (int) Math.max(0, sellableCount - 1), v.getWholesaleMinQty(), v.getWholesalePrice(),
                p.getShortDescription(), optionLabel, v.getSwatchHex(),
                v.getOptionColor(), v.getOptionSize(),
                v.effectiveColorFamily() == null ? null : v.effectiveColorFamily().name(),
                v.getSizePosition(), p.getBrand() == null ? null : p.getBrand().getName(),
                cover == null ? null : cover.focus(), v.isKit());
    }

    /** An option's cover photo and where on it the part that changes sits ("x% y%", V66; null = the middle). */
    private record Cover(String url, String focus) {}

    /**
     * What each variant on a page can sell right now. A plain variant sells what its own stock holds; a kit
     * holds none, so it sells however many whole kits its components can still make up. Without that, every
     * kit read "in stock" and a sold-out one was only refused at the last step of checkout.
     */
    private Map<UUID, BigDecimal> availability(List<ProductVariant> variants) {
        List<UUID> ids = variants.stream().map(ProductVariant::getId).toList();
        Map<UUID, BigDecimal> result = new HashMap<>(availabilityService.available(ids));
        List<UUID> kitVariantIds = variants.stream().filter(ProductVariant::isKit).map(ProductVariant::getId).toList();
        if (!kitVariantIds.isEmpty()) {
            result.putAll(kitAvailability.wholeKitsAvailable(kitVariantIds));
        }
        return result;
    }

    private Map<UUID, Long> sellableVariantCountByProduct(List<UUID> productIds) {
        return variantRepository.findByProductIdInAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNull(productIds)
                .stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId(), Collectors.counting()));
    }

    /**
     * Keyed by variant id: that option's own cover photo, or absent when it has none. A card never borrows a
     * sibling's photo or the product's: a photo belongs to exactly one option (see {@link PhotoOwnership}).
     */
    private Map<UUID, Cover> imageByVariant(List<ProductVariant> variants) {
        List<UUID> productIds = variants.stream().map(v -> v.getProduct().getId()).distinct().toList();
        List<ProductImage> images = imageRepository.findByProductIdInOrderBySortOrderAsc(productIds);
        Map<UUID, Long> optionCounts = PhotoOwnership.optionCounts(
                variantRepository.findByProductIdInAndDeletedFalse(productIds));
        Map<String, String> focusByUrl = new HashMap<>();
        for (ProductImage i : images) {
            if (i.getFocus() != null) focusByUrl.putIfAbsent(i.getUrl(), i.getFocus());
        }
        Map<UUID, Cover> out = new HashMap<>();
        PhotoOwnership.coverUrlByVariant(variants, images, optionCounts)
                .forEach((id, url) -> out.put(id, new Cover(url, focusByUrl.get(url))));
        return out;
    }

    @Transactional(readOnly = true)
    public List<CatalogCategoryDto> listCategories() {
        boolean demoOn = categoryRepository.demoDataOn();
        return categoryRepository.findByIsPublishedTrueOrderBySortOrderAsc().stream()
                .filter(c -> (c.getParent() == null && !c.isMockData()) || c.isMockData() == demoOn)
                .map(CatalogCategoryDto::from)
                .toList();
    }

    @Transactional
    public void recordView(UUID productId, UUID variantId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        ProductViewEvent event = new ProductViewEvent();
        event.setProduct(product);
        if (variantId != null) {
            event.setVariant(variantRepository.findById(variantId)
                    .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId)));
        }
        event.setOccurredAt(Instant.now(clock));
        viewEventRepository.save(event);
    }

    private Map<UUID, ProductReviewRepository.RatingSummary> ratingSummaries(List<UUID> productIds) {
        return reviewRepository.aggregateRatings(productIds, ProductReview.ReviewStatus.APPROVED).stream()
                .collect(Collectors.toMap(ProductReviewRepository.RatingSummary::getProductId, r -> r));
    }

    private Map<UUID, BigDecimal> soldSummariesByVariant(List<UUID> variantIds) {
        if (variantIds.isEmpty()) return Map.of();
        return saleEventRepository.aggregateUnitsSoldByVariant(variantIds).stream()
                .collect(Collectors.toMap(ProductSaleEventRepository.VariantSoldSummary::getVariantId,
                        ProductSaleEventRepository.VariantSoldSummary::getTotalQty));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
