package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.CartItemDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Guest cart — no reservation happens here. Founder's confirmed decision
 * (session 5): "don't hold at add-to-cart; hold for 15 min at checkout" — an
 * abandoned cart must never starve real buyers of a popular item. This
 * service only checks CURRENT availability as a courtesy at add-time (so a
 * customer isn't told "in stock" for something with zero left); the actual
 * guarantee against overselling happens once at checkout, when
 * {@link com.nbh.backend.order.service.CheckoutService} calls
 * {@link com.nbh.backend.stock.service.StockReservationService#reserve}.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final StockAvailabilityService availabilityService;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaignService;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository variantRepository,
                        StockAvailabilityService availabilityService,
                        EffectivePriceResolver priceResolver,
                        PromoCampaignService promoCampaignService) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.availabilityService = availabilityService;
        this.priceResolver = priceResolver;
        this.promoCampaignService = promoCampaignService;
    }

    @Transactional
    public CartDto getOrCreateCart(String sessionId) {
        Cart cart = findOrCreateCart(sessionId);
        return toDto(cart);
    }

    @Transactional
    public CartDto addItem(String sessionId, UUID variantId, BigDecimal qty) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        Cart cart = findOrCreateCart(sessionId);
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No product variant: " + variantId));
        requireSellable(variant);

        CartItem item = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId)
                .orElseGet(() -> {
                    CartItem newItem = new CartItem();
                    newItem.setCart(cart);
                    newItem.setVariant(variant);
                    newItem.setQty(BigDecimal.ZERO);
                    return newItem;
                });
        // The whole line, not just this click: with 3 in stock and 2 already in the basket,
        // adding 2 more used to pass because each add was compared to stock on its own.
        checkCourtesyAvailability(variant, item.getQty().add(qty));
        item.setQty(item.getQty().add(qty));
        // The real, server-computed price — a variant's own deal, else an active site-wide campaign, else
        // the plain selling price (see EffectivePriceResolver). This snapshot is what checkout later
        // trusts without re-resolving, so it must be right here, not just at display time.
        item.setUnitPriceSnapshot(priceResolver.resolve(variant, promoCampaignService.getActiveOrNull()).effectivePrice());
        item.setProductNameSnapshot(variant.getProduct().getName());
        item.setVariantNameSnapshot(variant.getVariantName());
        cartItemRepository.save(item);

        return toDto(cart);
    }

    @Transactional
    public CartDto updateItemQty(String sessionId, UUID itemId, BigDecimal qty) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive — use the remove endpoint to delete a line");
        }
        Cart cart = findOrCreateCart(sessionId);
        CartItem item = cartItemRepository.findById(itemId)
                .filter(i -> i.getCart().getId().equals(cart.getId()))
                .orElseThrow(() -> new EntityNotFoundException("No cart item: " + itemId));

        requireSellable(item.getVariant());
        checkCourtesyAvailability(item.getVariant(), qty);
        item.setQty(qty);
        cartItemRepository.save(item);

        return toDto(cart);
    }

    @Transactional
    public CartDto removeItem(String sessionId, UUID itemId) {
        Cart cart = findOrCreateCart(sessionId);
        CartItem item = cartItemRepository.findById(itemId)
                .filter(i -> i.getCart().getId().equals(cart.getId()))
                .orElseThrow(() -> new EntityNotFoundException("No cart item: " + itemId));
        cartItemRepository.delete(item);
        return toDto(cart);
    }

    private Cart findOrCreateCart(String sessionId) {
        return cartRepository.findBySessionId(sessionId).map(this::reactivateIfClosed).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setSessionId(sessionId);
            return cartRepository.save(cart);
        });
    }

    /**
     * session_id is unique, so a browser that checked out and then keeps
     * shopping under the same session can't get a second cart row. A closed
     * (CONVERTED/ABANDONED) cart is therefore emptied and reopened — its lines
     * already live on as order items, so nothing is lost, and the customer
     * never sees last order's items reappear in a new basket.
     */
    private Cart reactivateIfClosed(Cart cart) {
        if (cart.getStatus() == Cart.CartStatus.ACTIVE) {
            return cart;
        }
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cart.setStatus(Cart.CartStatus.ACTIVE);
        return cartRepository.save(cart);
    }

    /**
     * A deleted/hidden variant, or one whose product is unpublished/deleted, must never be
     * addable to a cart — found live while building Phase 3's editor (2026-09-24): nothing
     * previously checked this, so a variant an admin had just soft-deleted or hidden stayed
     * addable to a cart that already had a reference to it (e.g. reopened from local storage).
     */
    private void requireSellable(ProductVariant variant) {
        if (!variant.isSellable() || !variant.getProduct().isPublished() || variant.getProduct().isDeleted()) {
            throw new IllegalArgumentException("This item is no longer available");
        }
    }

    /**
     * What a shopper is told when the basket line asks for more than is in stock. The exact stock
     * figure is only shown when it is low (Odoo's website_sale_stock does the same with its
     * available_threshold, default 5) — anything higher tells a competitor how deep the shelf is,
     * and the old message also printed the internal SKU.
     */
    private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

    private void checkCourtesyAvailability(ProductVariant variant, BigDecimal requestedQty) {
        BigDecimal available = availabilityService.available(variant.getId());
        if (available.compareTo(requestedQty) >= 0) {
            return;
        }
        String name = variant.getProduct().getName()
                + (variant.getVariantName().equalsIgnoreCase("Default") ? "" : " (" + variant.getVariantName() + ")");
        if (available.signum() <= 0) {
            throw new IllegalArgumentException(name + " is out of stock");
        }
        if (available.compareTo(LOW_STOCK_THRESHOLD) <= 0) {
            throw new IllegalArgumentException(
                    "Only " + available.stripTrailingZeros().toPlainString() + " of " + name + " left");
        }
        throw new IllegalArgumentException("That quantity of " + name + " isn't available right now");
    }

    private CartDto toDto(Cart cart) {
        List<CartItemDto> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .map(CartItemDto::from)
                .toList();
        return CartDto.from(cart, items);
    }
}
