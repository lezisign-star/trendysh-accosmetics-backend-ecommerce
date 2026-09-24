package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The guest cart's own rules: a basket that was already checked out must come
 * back empty and usable (session_id is unique, so it can't be replaced by a
 * second row), lines merge per variant, and add-time availability is only a
 * courtesy check — not a reservation.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceUnitTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private PromoCampaignService promoCampaignService;

    private CartService cartService;
    private Cart cart;
    private ProductVariant variant;

    private static final String SESSION = "guest-session";

    @BeforeEach
    void setUp() {
        // A real resolver (pure function, no repository of its own) plus a mocked campaign service
        // returning no active campaign — these tests aren't about pricing, so the plain selling
        // price is what should end up in unitPriceSnapshot, same as before this dependency existed.
        lenient().when(promoCampaignService.getActiveOrNull()).thenReturn(null);
        cartService = new CartService(cartRepository, cartItemRepository, variantRepository,
                availabilityService, new EffectivePriceResolver(Clock.systemUTC()), promoCampaignService);

        cart = new Cart();
        cart.setId(UUID.randomUUID());
        cart.setSessionId(SESSION);
        cart.setStatus(Cart.CartStatus.ACTIVE);
        lenient().when(cartRepository.findBySessionId(SESSION)).thenReturn(Optional.of(cart));
        lenient().when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("Gel polish");
        product.setSlug("gel-polish");
        product.setPublished(true);
        variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku("GEL-RED");
        variant.setVariantName("Red");
        variant.setSellingPrice(new BigDecimal("800.00"));
        lenient().when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
    }

    private void stockAvailable(String onHand, String reserved) {
        when(availabilityService.available(variant.getId()))
                .thenReturn(new BigDecimal(onHand).subtract(new BigDecimal(reserved)));
    }

    @Test
    void aConvertedBasketIsEmptiedAndReopenedInsteadOfLeakingLastOrdersLines() {
        cart.setStatus(Cart.CartStatus.CONVERTED);
        CartItem old = new CartItem();
        old.setVariant(variant);
        old.setQty(BigDecimal.TEN);
        old.setUnitPriceSnapshot(new BigDecimal("800.00"));
        old.setProductNameSnapshot("Gel polish");
        old.setVariantNameSnapshot("Red");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(old)).thenReturn(List.of());

        CartDto dto = cartService.getOrCreateCart(SESSION);

        assertThat(cart.getStatus()).isEqualTo(Cart.CartStatus.ACTIVE);
        verify(cartItemRepository).deleteAll(List.of(old));
        assertThat(dto.items()).isEmpty();
    }

    @Test
    void addingTheSameVariantTwiceMergesIntoOneLineWithProductIdentityExposed() {
        stockAvailable("10", "0");
        CartItem[] saved = new CartItem[1];
        when(cartItemRepository.findByCartIdAndVariantId(cart.getId(), variant.getId()))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(saved[0]));
        when(cartItemRepository.save(any())).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        when(cartItemRepository.findByCartId(cart.getId())).thenAnswer(inv -> List.of(saved[0]));

        cartService.addItem(SESSION, variant.getId(), new BigDecimal("2"));
        CartDto dto = cartService.addItem(SESSION, variant.getId(), new BigDecimal("3"));

        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).qty()).isEqualByComparingTo("5");
        assertThat(dto.items().get(0).productSlug()).isEqualTo("gel-polish");
        assertThat(dto.items().get(0).productId()).isEqualTo(variant.getProduct().getId());
        assertThat(dto.subtotal()).isEqualByComparingTo("4000.00");
    }

    @Test
    void addingMoreThanIsAvailableIsRefusedAndNothingIsSaved() {
        stockAvailable("5", "3");

        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GEL-RED");
        verify(cartItemRepository, never()).save(any());
    }
}
