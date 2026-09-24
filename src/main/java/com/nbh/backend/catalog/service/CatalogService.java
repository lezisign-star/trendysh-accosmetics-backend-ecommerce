package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.*;
import com.nbh.backend.catalog.entity.*;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
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
 * Every method here only ever returns published, non-deleted products: a
 * product mid-edit (no photos yet, pricing not finalised) must never leak
 * onto the storefront just because a customer guesses its slug.
 */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductImageRepository imageRepository;
    private final VariantAttributeValueRepository variantAttributeValueRepository;
    private final ProductReviewRepository reviewRepository;
    private final ProductSaleEventRepository saleEventRepository;
    private final ProductViewEventRepository viewEventRepository;
    private final CategoryRepository categoryRepository;
    private final StockBalanceRepository balanceRepository;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaignService;
    private final Clock clock;

    public CatalogService(ProductRepository productRepository,
                           ProductVariantRepository variantRepository,
                           ProductImageRepository imageRepository,
                           VariantAttributeValueRepository variantAttributeValueRepository,
                           ProductReviewRepository reviewRepository,
                           ProductSaleEventRepository saleEventRepository,
                           ProductViewEventRepository viewEventRepository,
                           CategoryRepository categoryRepository,
                           StockBalanceRepository balanceRepository,
                           EffectivePriceResolver priceResolver,
                           PromoCampaignService promoCampaignService,
                           Clock clock) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.imageRepository = imageRepository;
        this.variantAttributeValueRepository = variantAttributeValueRepository;
        this.reviewRepository = reviewRepository;
        this.saleEventRepository = saleEventRepository;
        this.viewEventRepository = viewEventRepository;
        this.categoryRepository = categoryRepository;
        this.balanceRepository = balanceRepository;
        this.priceResolver = priceResolver;
        this.promoCampaignService = promoCampaignService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<CatalogProductSummaryDto> browse(String q, String categorySlug, Boolean deal, Boolean popular,
                                                  Boolean newArrival, Pageable pageable) {
        Page<Product> page = productRepository.searchPublished(blankToNull(q), blankToNull(categorySlug),
                deal, popular, newArrival, pageable);
        List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();
        if (productIds.isEmpty()) {
            return org.springframework.data.domain.Page.empty(pageable);
        }

        Map<UUID, List<ProductVariant>> variantsByProduct = groupVariantsByProduct(productIds);
        Map<UUID, String> primaryImageByProduct = primaryImageByProduct(productIds);
        Map<UUID, ProductReviewRepository.RatingSummary> ratingByProduct = ratingSummaries(productIds);
        Map<UUID, BigDecimal> soldByProduct = soldSummaries(productIds);
        Set<UUID> inStockVariants = inStockVariantIds(variantsByProduct);
        PromoCampaign activeCampaign = promoCampaignService.getActiveOrNull();

        return page.map(p -> {
            List<ProductVariant> variants = variantsByProduct.getOrDefault(p.getId(), List.of());
            List<EffectivePriceResolver.EffectivePrice> prices = variants.stream()
                    .map(v -> priceResolver.resolve(v, activeCampaign)).toList();
            ProductReviewRepository.RatingSummary rating = ratingByProduct.get(p.getId());
            return CatalogProductSummaryDto.from(p, prices, primaryImageByProduct.get(p.getId()),
                    rating != null ? rating.getAvgRating() : 0.0,
                    rating != null ? rating.getReviewCount() : 0L,
                    soldByProduct.getOrDefault(p.getId(), BigDecimal.ZERO),
                    variants.stream().anyMatch(v -> inStockVariants.contains(v.getId())));
        });
    }

    @Transactional(readOnly = true)
    public CatalogProductDetailDto getDetail(String slug) {
        Product product = productRepository.findBySlugAndIsPublishedTrueAndDeletedFalse(slug)
                .orElseThrow(() -> new EntityNotFoundException("No published product: " + slug));

        List<ProductVariant> variants = variantRepository.findByProductId(product.getId());
        List<UUID> variantIds = variants.stream().map(ProductVariant::getId).toList();
        List<VariantAttributeValue> attributeValues = variantIds.isEmpty()
                ? List.of() : variantAttributeValueRepository.findByVariantIdIn(variantIds);
        Set<UUID> inStockVariants = inStockVariantIds(Map.of(product.getId(), variants));
        PromoCampaign activeCampaign = promoCampaignService.getActiveOrNull();
        List<CatalogVariantDto> variantDtos = variants.stream()
                .map(v -> CatalogVariantDto.from(v, attributeValues, inStockVariants.contains(v.getId()),
                        priceResolver.resolve(v, activeCampaign)))
                .toList();

        List<CatalogImageDto> images = imageRepository.findByProductIdOrderBySortOrderAsc(product.getId()).stream()
                .map(CatalogImageDto::from)
                .toList();

        double avgRating = Optional.ofNullable(reviewRepository.aggregateRatings(List.of(product.getId()), ProductReview.ReviewStatus.APPROVED))
                .filter(l -> !l.isEmpty()).map(l -> l.get(0).getAvgRating()).orElse(0.0);
        long reviewCount = reviewRepository.countByProductIdAndStatus(product.getId(), ProductReview.ReviewStatus.APPROVED);
        BigDecimal unitsSold = saleEventRepository.totalUnitsSold(product.getId());

        return CatalogProductDetailDto.from(product, images, variantDtos, avgRating, reviewCount, unitsSold);
    }

    @Transactional(readOnly = true)
    public List<CatalogProductSummaryDto> related(UUID productId, int limit) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("No product: " + productId));
        List<UUID> categoryIds = product.getCategories().stream().map(Category::getId).toList();
        if (categoryIds.isEmpty()) {
            return List.of();
        }

        List<Product> related = productRepository.findRelated(categoryIds, productId,
                PageRequest.of(0, limit));
        List<UUID> relatedIds = related.stream().map(Product::getId).toList();
        if (relatedIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ProductVariant>> variantsByProduct = groupVariantsByProduct(relatedIds);
        Map<UUID, String> primaryImageByProduct = primaryImageByProduct(relatedIds);
        Map<UUID, ProductReviewRepository.RatingSummary> ratingByProduct = ratingSummaries(relatedIds);
        Map<UUID, BigDecimal> soldByProduct = soldSummaries(relatedIds);
        Set<UUID> inStockVariants = inStockVariantIds(variantsByProduct);
        PromoCampaign activeCampaign = promoCampaignService.getActiveOrNull();

        return related.stream().map(p -> {
            List<ProductVariant> variants = variantsByProduct.getOrDefault(p.getId(), List.of());
            List<EffectivePriceResolver.EffectivePrice> prices = variants.stream()
                    .map(v -> priceResolver.resolve(v, activeCampaign)).toList();
            ProductReviewRepository.RatingSummary rating = ratingByProduct.get(p.getId());
            return CatalogProductSummaryDto.from(p, prices, primaryImageByProduct.get(p.getId()),
                    rating != null ? rating.getAvgRating() : 0.0,
                    rating != null ? rating.getReviewCount() : 0L,
                    soldByProduct.getOrDefault(p.getId(), BigDecimal.ZERO),
                    variants.stream().anyMatch(v -> inStockVariants.contains(v.getId())));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogCategoryDto> listCategories() {
        return categoryRepository.findByIsPublishedTrueOrderBySortOrderAsc().stream()
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

    /**
     * Variants with sellable stock right now (on-hand minus reserved, across
     * physical locations, strictly positive). One batched query for the whole
     * page; the customer only ever sees the boolean, never the quantity.
     */
    private Set<UUID> inStockVariantIds(Map<UUID, List<ProductVariant>> variantsByProduct) {
        List<UUID> variantIds = variantsByProduct.values().stream()
                .flatMap(List::stream).map(ProductVariant::getId).toList();
        if (variantIds.isEmpty()) {
            return Set.of();
        }
        return balanceRepository.availabilityForVariants(variantIds).stream()
                .filter(a -> a.getAvailable().signum() > 0)
                .map(StockBalanceRepository.VariantAvailability::getVariantId)
                .collect(Collectors.toSet());
    }

    private Map<UUID, List<ProductVariant>> groupVariantsByProduct(List<UUID> productIds) {
        return variantRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
    }

    private Map<UUID, String> primaryImageByProduct(List<UUID> productIds) {
        Map<UUID, String> result = new HashMap<>();
        for (ProductImage image : imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)) {
            UUID pid = image.getProduct().getId();
            if (!result.containsKey(pid) || image.isPrimary()) {
                result.put(pid, image.getUrl());
            }
        }
        return result;
    }

    private Map<UUID, ProductReviewRepository.RatingSummary> ratingSummaries(List<UUID> productIds) {
        return reviewRepository.aggregateRatings(productIds, ProductReview.ReviewStatus.APPROVED).stream()
                .collect(Collectors.toMap(ProductReviewRepository.RatingSummary::getProductId, r -> r));
    }

    private Map<UUID, BigDecimal> soldSummaries(List<UUID> productIds) {
        return saleEventRepository.aggregateUnitsSold(productIds).stream()
                .collect(Collectors.toMap(ProductSaleEventRepository.SoldSummary::getProductId,
                        ProductSaleEventRepository.SoldSummary::getTotalQty));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
