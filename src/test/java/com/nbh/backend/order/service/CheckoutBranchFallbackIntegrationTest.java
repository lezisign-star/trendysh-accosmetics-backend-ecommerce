package com.nbh.backend.order.service;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.stock.entity.StockDocument;
import com.nbh.backend.stock.entity.StockDocumentLine;
import com.nbh.backend.stock.entity.StockDocumentType;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.service.StockPostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The owner's confirmed allocation rule, against the real checkout path: the main branch (BR-A,
 * seeded as the default online-fulfilment branch) takes a line unless it's short, in which case
 * another active branch (BR-B) covers it instead — and only a genuine everywhere-out-of-stock line
 * fails checkout, with a customer-facing message that names the product, never an internal branch
 * or location code (the owner's explicit requirement).
 */
@SpringBootTest
@ActiveProfiles("test")
class CheckoutBranchFallbackIntegrationTest {

    @Autowired private BranchRepository branchRepository;
    @Autowired private StockLocationRepository locationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private StockPostingService postingService;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private CheckoutService checkoutService;

    private ProductVariant seedVariantWithStock(BigDecimal atBranchA, BigDecimal atBranchB) {
        Branch branchA = branchRepository.findByCode("BR-A").orElseThrow();
        Branch branchB = branchRepository.findByCode("BR-B").orElseThrow();
        StockLocation floorA = locationRepository.findByBranchIdAndLocationType(branchA.getId(), LocationType.SALES_FLOOR).orElseThrow();
        StockLocation floorB = locationRepository.findByBranchIdAndLocationType(branchB.getId(), LocationType.SALES_FLOOR).orElseThrow();
        StockLocation supplier = locationRepository.findByCode("SUPPLIER").orElseThrow();

        Product product = new Product();
        product.setSlug("fallback-test-" + UUID.randomUUID());
        product.setName("Fallback Test Product");
        product = productRepository.save(product);

        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setSku("FALLBACK-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Default");
        variant.setSellingPrice(new BigDecimal("1000.00"));
        variant = variantRepository.save(variant);

        receive(variant, supplier, floorA, atBranchA, branchA);
        receive(variant, supplier, floorB, atBranchB, branchB);
        return variant;
    }

    private void receive(ProductVariant variant, StockLocation supplier, StockLocation target,
                          BigDecimal qty, Branch branch) {
        if (qty.signum() <= 0) return;
        StockDocument receipt = new StockDocument();
        receipt.setDocumentNo("GRN-FALLBACK-" + UUID.randomUUID().toString().substring(0, 8));
        receipt.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        receipt.setBranch(branch);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(receipt);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceLocation(supplier);
        line.setTargetLocation(target);
        line.setQty(qty);
        line.setUnitCost(new BigDecimal("500.00"));
        receipt.getLines().add(line);

        postingService.post(receipt);
    }

    private String cartWithOneLine(ProductVariant variant, BigDecimal qty) {
        String sessionId = "fallback-test-" + UUID.randomUUID();
        Cart cart = new Cart();
        cart.setSessionId(sessionId);
        cart.setStatus(Cart.CartStatus.ACTIVE);
        cart = cartRepository.save(cart);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setVariant(variant);
        item.setQty(qty);
        item.setUnitPriceSnapshot(variant.getSellingPrice());
        item.setProductNameSnapshot(variant.getProduct().getName());
        item.setVariantNameSnapshot(variant.getVariantName());
        cartItemRepository.save(item);

        return sessionId;
    }

    private CheckoutRequest request() {
        return new CheckoutRequest("Fallback Tester", "fallback-tester@example.com", "254700000111",
                FulfilmentType.BRANCH_PICKUP, null, null);
    }

    @Test
    void mainBranchCoversTheLineWhenItHasEnoughOnItsOwn() {
        ProductVariant variant = seedVariantWithStock(new BigDecimal("10"), new BigDecimal("10"));
        String sessionId = cartWithOneLine(variant, new BigDecimal("3"));

        var result = checkoutService.checkout(sessionId, request());

        Branch branchA = branchRepository.findByCode("BR-A").orElseThrow();
        var reservedBranch = result.order().getItems().get(0).getReservation().getLocation().getBranch();
        assertThat(reservedBranch.getId()).isEqualTo(branchA.getId());
    }

    @Test
    void anotherBranchCoversTheLineWhenTheMainBranchIsShort() {
        // Main branch (BR-A) has only 2; BR-B has 10 — the line for 5 must fall back to BR-B whole.
        ProductVariant variant = seedVariantWithStock(new BigDecimal("2"), new BigDecimal("10"));
        String sessionId = cartWithOneLine(variant, new BigDecimal("5"));

        var result = checkoutService.checkout(sessionId, request());

        Branch branchB = branchRepository.findByCode("BR-B").orElseThrow();
        var reservedBranch = result.order().getItems().get(0).getReservation().getLocation().getBranch();
        assertThat(reservedBranch.getId()).isEqualTo(branchB.getId());
    }

    @Test
    void checkoutFailsWithACustomerFriendlyMessageWhenNoSingleBranchHasEnough() {
        // Neither branch alone has 11, even though the two combined would.
        ProductVariant variant = seedVariantWithStock(new BigDecimal("2"), new BigDecimal("10"));
        String sessionId = cartWithOneLine(variant, new BigDecimal("11"));

        assertThatThrownBy(() -> checkoutService.checkout(sessionId, request()))
                .isInstanceOf(InsufficientAvailabilityException.class)
                .hasMessageContaining("Fallback Test Product")
                .hasMessageContaining("out of stock")
                .hasMessageNotContaining("SALES_FLOOR")
                .hasMessageNotContaining("BR-A")
                .hasMessageNotContaining("BR-B");
    }
}
