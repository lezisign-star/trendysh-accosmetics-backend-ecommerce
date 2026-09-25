package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.CatalogProductDetailDto;
import com.nbh.backend.catalog.dto.CatalogVariantDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The public catalogue's inStock flag. Result-driven: asserts on the DTO a
 * customer would actually receive. Only a strictly positive sellable quantity
 * counts as in stock, and a product is in stock if ANY of its variants is.
 */
@ExtendWith(MockitoExtension.class)
class CatalogServiceInStockUnitTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private VariantAttributeValueRepository variantAttributeValueRepository;
    @Mock private ProductReviewRepository reviewRepository;
    @Mock private ProductSaleEventRepository saleEventRepository;
    @Mock private ProductViewEventRepository viewEventRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private PromoCampaignService promoCampaignService;

    private CatalogService catalogService;
    private Product product;
    private ProductVariant inStockVariant;
    private ProductVariant zeroVariant;
    private ProductVariant noBalanceVariant;

    @BeforeEach
    void setUp() {
        // A real resolver (not mocked) — it's a pure function needing no repository of its own, and
        // stubbing its return value for every variant here would obscure that price isn't what these
        // tests are about. promoCampaignService is mocked and left unstubbed (no campaign in play);
        // lenient() since not every test method necessarily reaches the code path that calls it.
        org.mockito.Mockito.lenient().when(promoCampaignService.getActiveOrNull()).thenReturn(null);
        catalogService = new CatalogService(productRepository, variantRepository, imageRepository,
                variantAttributeValueRepository, reviewRepository, saleEventRepository, viewEventRepository,
                categoryRepository, availabilityService, new EffectivePriceResolver(Clock.systemUTC()),
                promoCampaignService, Clock.systemUTC());

        product = new Product();
        product.setId(UUID.randomUUID());
        product.setSlug("test-product");
        product.setName("Test Product");

        inStockVariant = variant("SKU-IN");
        zeroVariant = variant("SKU-ZERO");
        noBalanceVariant = variant("SKU-NONE");

        when(productRepository.findBySlugAndIsPublishedTrueAndDeletedFalse("test-product"))
                .thenReturn(Optional.of(product));
        when(variantAttributeValueRepository.findByVariantIdIn(any())).thenReturn(List.of());
        when(imageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of());
        when(reviewRepository.aggregateRatings(any(), any())).thenReturn(List.of());
        when(reviewRepository.countByProductIdAndStatus(any(), any())).thenReturn(0L);
        when(saleEventRepository.totalUnitsSold(product.getId())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void onlyVariantsWithStrictlyPositiveSellableQuantityAreInStockAndProductFollowsAnyVariant() {
        when(variantRepository.findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(product.getId()))
                .thenReturn(List.of(inStockVariant, zeroVariant, noBalanceVariant));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(
                inStockVariant.getId(), new BigDecimal("5"), zeroVariant.getId(), BigDecimal.ZERO));

        CatalogProductDetailDto detail = catalogService.getDetail("test-product");

        assertThat(detail.variants()).extracting(CatalogVariantDto::sku, CatalogVariantDto::inStock)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("SKU-IN", true),
                        org.assertj.core.groups.Tuple.tuple("SKU-ZERO", false),
                        org.assertj.core.groups.Tuple.tuple("SKU-NONE", false));
        assertThat(detail.inStock()).isTrue();
    }

    @Test
    void productIsOutOfStockWhenNoVariantHasSellableQuantity() {
        when(variantRepository.findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(product.getId()))
                .thenReturn(List.of(zeroVariant, noBalanceVariant));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(zeroVariant.getId(), new BigDecimal("-2")));

        CatalogProductDetailDto detail = catalogService.getDetail("test-product");

        assertThat(detail.inStock()).isFalse();
        assertThat(detail.variants()).allMatch(v -> !v.inStock());
    }

    private ProductVariant variant(String sku) {
        ProductVariant v = new ProductVariant();
        v.setId(UUID.randomUUID());
        v.setProduct(product);
        v.setSku(sku);
        v.setVariantName(sku);
        v.setSellingPrice(new BigDecimal("100.00"));
        return v;
    }
}
