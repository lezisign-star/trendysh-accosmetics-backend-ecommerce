package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.CatalogVariantDetailDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.packages.service.PackageAvailabilityService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A card's recent sales (owner, 2026-10-28: "Popular" shows only real sales): the last 30 days, and never anything sold
 * before the shop opened on Monday 2 November 2026 at 07:00 Nairobi, so test orders before the launch do not count.
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceRecentSalesUnitTest {

    private static final Instant OPENED = Instant.parse("2026-11-02T04:00:00Z");

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private ProductReviewRepository reviewRepository;
    @Mock private ProductSaleEventRepository saleEventRepository;
    @Mock private ProductViewEventRepository viewEventRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private PackageAvailabilityService packageAvailability;
    @Mock private PromoCampaignService promoCampaignService;
    @Mock private ProductOptionValueRepository optionRepository;

    private Instant countedFrom(Instant now) {
        lenient().when(promoCampaignService.getActiveOrNull()).thenReturn(null);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        CatalogService service = new CatalogService(productRepository, variantRepository, imageRepository,
                reviewRepository, saleEventRepository, viewEventRepository, categoryRepository, availabilityService,
                packageAvailability, new EffectivePriceResolver(clock), promoCampaignService, optionRepository, clock);
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSlug("p");
        product.setName("Gel");
        product.setPublished(true);
        ProductVariant v = new ProductVariant();
        v.setId(UUID.randomUUID());
        v.setProduct(product);
        v.setSku("GEL-1");
        v.setVariantName("GEL-1");
        v.setSellingPrice(new BigDecimal("100.00"));
        v.setActive(true);
        when(imageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of());
        when(reviewRepository.aggregateRatings(any(), any())).thenReturn(List.of());
        when(variantRepository.findById(v.getId())).thenReturn(Optional.of(v));
        when(variantRepository.findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(product.getId()))
                .thenReturn(List.of(v));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(v.getId(), BigDecimal.ONE));

        CatalogVariantDetailDto detail = service.variantDetail(v.getId());
        assertThat(detail.card().recentUnitsSold()).isEqualByComparingTo("0");

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(saleEventRepository, org.mockito.Mockito.atLeastOnce())
                .aggregateUnitsSoldByVariantSince(any(), since.capture());
        return since.getValue();
    }

    @Test
    void inTheFirstMonthNothingSoldBeforeTheShopOpenedCounts() {
        assertThat(countedFrom(Instant.parse("2026-11-10T09:00:00Z"))).isEqualTo(OPENED);
    }

    @Test
    void laterOnlyTheLast30DaysCount() {
        Instant now = Instant.parse("2026-12-20T09:00:00Z");
        assertThat(countedFrom(now)).isEqualTo(now.minus(java.time.Duration.ofDays(30)));
    }
}
