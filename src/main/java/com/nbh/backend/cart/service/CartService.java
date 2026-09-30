package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.CartItemDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.common.exception.PriceChangedException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.stock.service.StockAvailabilityService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * What this session's basket holds, in KES, at TODAY's prices — the figure checkout sizes a TumaBoda
     * delivery by, so a price previewed here and the price charged at checkout cannot disagree. Zero for
     * an unknown, empty or already-checked-out session, and unlike {@link #getOrCreateCart} it never
     * creates a cart (or writes anything: the basket itself is re-priced when it is read).
     */
    @Transactional(readOnly = true)
    public BigDecimal basketValue(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return BigDecimal.ZERO;
        }
        PromoCampaign campaign = promoCampaignService.getActiveOrNull();
        return cartRepository.findBySessionId(sessionId)
                .filter(cart -> cart.getStatus() == Cart.CartStatus.ACTIVE)
                .map(cart -> cartItemRepository.findByCartId(cart.getId()).stream()
                        .map(item -> currentPrice(item, campaign).effectivePrice().multiply(item.getQty()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * The first thing checkout does with a basket: bring every line up to today's price and make sure
     * everything in it can still be bought, then — if the caller says what total the shopper was shown —
     * refuse (409 {@code PRICE_CHANGED}) when the real total is different, so nobody is ever charged an
     * amount they did not see. Runs in the caller's transaction, so a refusal leaves nothing behind.
     *
     * @param expectedSubtotal what the shopper was shown, or null to skip the comparison (older clients)
     */
    @Transactional
    public void refreshPricesForCheckout(String sessionId, BigDecimal expectedSubtotal) {
        Cart cart = cartRepository.findBySessionId(sessionId)
                .filter(c -> c.getStatus() == Cart.CartStatus.ACTIVE)
                .orElse(null);
        if (cart == null) {
            return; // nothing to price; checkout itself reports a missing or already-converted basket
        }
        Refreshed refreshed = refreshPrices(cart);
        for (CartItem item : refreshed.items()) {
            if (!isPurchasable(item.getVariant())) {
                throw new UnprocessableException(item.getProductNameSnapshot()
                        + " is no longer available. Please remove it from your basket and try again.");
            }
        }
        BigDecimal subtotal = refreshed.items().stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (expectedSubtotal != null && expectedSubtotal.compareTo(subtotal) != 0) {
            throw new PriceChangedException("Some prices changed while you were checking out. We've updated your"
                    + " basket: please look over the new total and place your order again.");
        }
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
        applyPriceForQty(item);
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
        // Repriced here rather than left to toDto's refresh: crossing a wholesale threshold is the
        // shopper's own doing, not a price that moved under them, so it must not come back flagged
        // as previousUnitPrice ("was KSh X, now KSh Y").
        applyPriceForQty(item);
        cartItemRepository.save(item);

        return toDto(cart);
    }

    /**
     * The real, server-computed price for the line's current quantity — a variant's own deal, else an
     * active site-wide campaign, else its wholesale price if the quantity qualifies (cheapest of those
     * wins), else the plain selling price (see EffectivePriceResolver). This snapshot is what checkout
     * later trusts without re-resolving, so it must be right here, not just at display time.
     */
    private void applyPriceForQty(CartItem item) {
        EffectivePriceResolver.EffectivePrice price =
                priceResolver.resolve(item.getVariant(), promoCampaignService.getActiveOrNull(), item.getQty());
        item.setUnitPriceSnapshot(price.effectivePrice());
        item.setWholesaleApplied(price.source() == EffectivePriceResolver.Source.WHOLESALE);
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
        if (!isPurchasable(variant)) {
            throw new IllegalArgumentException("This item is no longer available");
        }
    }

    private static boolean isPurchasable(ProductVariant variant) {
        return variant.isSellable() && variant.getProduct().isPublished() && !variant.getProduct().isDeleted();
    }

    /**
     * What a shopper is told when the basket line asks for more than is in stock. The exact stock
     * figure is only shown when it is low (Odoo's website_sale_stock does the same with its
     * available_threshold, default 5) — anything higher tells a competitor how deep the shelf is,
     * and the old message also printed the internal SKU.
     */
    private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

    private void checkCourtesyAvailability(ProductVariant variant, BigDecimal requestedQty) {
        if (variant.isKit()) {
            // A kit holds no StockBalance of its own — the real stock lives on its components, and
            // real availability is enforced at checkout by reserving each of those instead.
            return;
        }
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

    /**
     * Every read of the basket first looks each line's price up again (a variant's deal, else an active
     * site-wide campaign, else the plain price — see {@link EffectivePriceResolver}) and stores it, so
     * the price shown and the price checkout charges are today's, never one remembered from whenever the
     * item went in. A price that moved is reported once, on the read that noticed it ({@code
     * previousUnitPrice}), so the shopper is told rather than surprised.
     *
     * <p>Replaces the old "snapshot at add time and trust it" rule, under which a deal that had ended
     * (or a price an admin had corrected) was still charged for as long as the item sat in a basket.
     */
    private CartDto toDto(Cart cart) {
        Refreshed refreshed = refreshPrices(cart);
        List<CartItemDto> items = refreshed.items().stream()
                .map(item -> CartItemDto.from(item, isPurchasable(item.getVariant()),
                        refreshed.changedFrom().get(item.getId())))
                .toList();
        return CartDto.from(cart, items);
    }

    private record Refreshed(List<CartItem> items, Map<UUID, BigDecimal> changedFrom) {}

    private Refreshed refreshPrices(Cart cart) {
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        PromoCampaign campaign = promoCampaignService.getActiveOrNull();
        Map<UUID, BigDecimal> changedFrom = new HashMap<>();
        for (CartItem item : items) {
            EffectivePriceResolver.EffectivePrice current = currentPrice(item, campaign);
            boolean wholesaleApplied = current.source() == EffectivePriceResolver.Source.WHOLESALE;
            if (current.effectivePrice().compareTo(item.getUnitPriceSnapshot()) != 0
                    || wholesaleApplied != item.isWholesaleApplied()) {
                if (current.effectivePrice().compareTo(item.getUnitPriceSnapshot()) != 0) {
                    changedFrom.put(item.getId(), item.getUnitPriceSnapshot());
                }
                item.setUnitPriceSnapshot(current.effectivePrice());
                item.setWholesaleApplied(wholesaleApplied);
                cartItemRepository.save(item);
            }
        }
        return new Refreshed(items, changedFrom);
    }

    /**
     * Today's price for a line. A product that can no longer be bought (unpublished, deleted, no price)
     * keeps the price it had: there is nothing to look up, and the line is reported as unavailable instead.
     */
    private EffectivePriceResolver.EffectivePrice currentPrice(CartItem item, PromoCampaign campaign) {
        ProductVariant variant = item.getVariant();
        if (!isPurchasable(variant)) {
            return new EffectivePriceResolver.EffectivePrice(item.getUnitPriceSnapshot(), item.getUnitPriceSnapshot(),
                    null, EffectivePriceResolver.Source.NONE);
        }
        return priceResolver.resolve(variant, campaign, item.getQty());
    }
}
