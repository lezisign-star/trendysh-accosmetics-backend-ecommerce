package com.nbh.backend.stock;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.InventoryLineDto;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 regression test (2026-09-27): before this fix, {@code browseInventory} and
 * {@code countBelowReorderLevel} both started FROM stock_balance, so a variant that has never
 * been received (no balance row exists at all) was invisible to the "browse stock levels"
 * screen and never counted as below its reorder level — even though zero stock is
 * definitionally at or below any positive reorder level. Both queries were rewritten to start
 * FROM ProductVariant with a LEFT JOIN to the (possibly absent) SHOP balance row.
 */
@SpringBootTest
@ActiveProfiles("test")
class NeverReceivedVariantVisibilityTest {

    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockBalanceRepository balanceRepository;

    @Test
    void aNeverReceivedVariantWithAReorderLevelAppearsAsBelowReorderAndIsCounted() {
        Product product = new Product();
        product.setSlug("never-received-test-" + UUID.randomUUID());
        product.setName("Never Received Test Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("NEVER-RECEIVED-" + UUID.randomUUID());
        variant.setVariantName("Test Variant");
        variant.setSellingPrice(new BigDecimal("1000.00"));
        variant.setReorderLevel(new BigDecimal("5"));
        variant = variantRepository.save(variant);
        // Deliberately no GOODS_RECEIPT posted — this variant has zero stock_balance rows.
        UUID variantId = variant.getId();

        var page = balanceRepository.browseInventory(true, false, false, null, null, PageRequest.of(0, 100));
        InventoryLineDto row = page.getContent().stream()
                .filter(l -> l.variantId().equals(variantId))
                .findFirst()
                .orElse(null);

        assertTrue(row != null, "a never-received variant with a reorder level must appear in the below-reorder browse");
        assertEquals(0, row.onHandQty().compareTo(BigDecimal.ZERO));
        assertEquals(0, row.availableQty().compareTo(BigDecimal.ZERO));
        assertTrue(row.belowReorderLevel());

        long countIncludingThisVariant = balanceRepository.countBelowReorderLevel();
        assertTrue(countIncludingThisVariant >= 1,
                "the low-stock tile's count must include a never-received variant, not just ones with a balance row");
    }
}
