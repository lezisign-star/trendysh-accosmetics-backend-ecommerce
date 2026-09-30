package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WholesalePricingServiceUnitTest {

    @Mock private ProductVariantRepository variantRepository;

    private WholesalePricingService service;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        service = new WholesalePricingService(variantRepository);

        product = new Product();
        product.setId(UUID.randomUUID());
        variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSellingPrice(new BigDecimal("1000"));
        lenient().when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        lenient().when(variantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void setsBothFieldsTogetherOnAValidRequest() {
        ProductVariant result = service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("700"));

        assertThat(result.getWholesaleMinQty()).isEqualTo(10);
        assertThat(result.getWholesalePrice()).isEqualByComparingTo("700");
    }

    @Test
    void clearingNullsBothFields() {
        service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("700"));

        ProductVariant result = service.clearWholesalePrice(product.getId(), variant.getId());

        assertThat(result.getWholesaleMinQty()).isNull();
        assertThat(result.getWholesalePrice()).isNull();
    }

    @Test
    void rejectsAZeroOrNegativePrice() {
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 10, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("-5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPriceThatIsNotBelowTheSellingPrice() {
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than the regular selling price");
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("1200")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhenTheVariantHasNoSellingPriceYet() {
        variant.setSellingPrice(null);

        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 10, new BigDecimal("700")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Set a price for this variant first");
    }

    @Test
    void rejectsAMinQtyOfOneOrLess() {
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 1, new BigDecimal("700")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
        assertThatThrownBy(() -> service.setWholesalePrice(product.getId(), variant.getId(), 0, new BigDecimal("700")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAVariantThatDoesNotBelongToTheGivenProduct() {
        UUID wrongProductId = UUID.randomUUID();

        assertThatThrownBy(() -> service.setWholesalePrice(wrongProductId, variant.getId(), 10, new BigDecimal("700")))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
