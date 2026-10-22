package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.CartItemDto;
import com.nbh.backend.cart.dto.KitPartDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.common.exception.PriceChangedException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.kits.service.KitAvailabilityService;
import com.nbh.backend.kits.service.KitChoices;
import com.nbh.backend.kits.service.KitService;
import com.nbh.backend.stock.service.QuantityRules;
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
 *
 * <p>A kit line may carry the shopper's picks (another colour or size of a product inside the kit, owner 2026-10-19):
 * the line is then identified by the kit AND its picks, priced at the kit's price plus the difference the picks make
 * ({@link KitService#priceDelta}) and counted against the shelves of the variants picked.
 */
@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductVariantRepository variantRepository;
    private final StockAvailabilityService availabilityService;
    private final KitAvailabilityService kitAvailability;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaignService;
    private final KitService kitService;
    private final VariantCovers variantCovers;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository variantRepository,
                        StockAvailabilityService availabilityService,
                        KitAvailabilityService kitAvailability,
                        EffectivePriceResolver priceResolver,
                        PromoCampaignService promoCampaignService,
                        KitService kitService,
                        VariantCovers variantCovers) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.availabilityService = availabilityService;
        this.kitAvailability = kitAvailability;
        this.priceResolver = priceResolver;
        this.promoCampaignService = promoCampaignService;
        this.kitService = kitService;
        this.variantCovers = variantCovers;
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
        // The basket is read UNDER the checkout lock, here, as the very first thing the transaction does. Checkout takes
        // the same lock a few lines later, but a lock does not refresh a basket this transaction has already read: a
        // second simultaneous submit (a double-click, a retry) used to read it open before the first had finished,
        // wait for the lock, then carry on with that stale copy and make a second order and a second stock hold.
        // Reading it under the lock makes the second submit wait first and then see it checked out.
        Cart cart = cartRepository.findWithLockBySessionId(sessionId)
                .filter(c -> c.getStatus() == Cart.CartStatus.ACTIVE)
                .orElse(null);
        if (cart == null) {
            return; // nothing to price; checkout itself reports a missing or already-converted basket
        }
        Refreshed refreshed = refreshPrices(cart);
        for (CartItem item : refreshed.items()) {
            if (item.getVariant().getProduct().isMockData()) {
                throw new UnprocessableException(demoProductMessage(item.getProductNameSnapshot())
                        + " Please remove it from your cart and try again.");
            }
            if (!isPurchasable(item.getVariant())) {
                throw new UnprocessableException(item.getProductNameSnapshot()
                        + " is no longer available. Please remove it from your cart and try again.");
            }
            // What the shopper picked inside a kit has to be buyable too, or the order could not be packed as shown.
            if (item.getVariant().isKit() && !item.getChoices().isEmpty()) {
                for (KitService.SelectedPart part : kitService.selectionFor(item.getVariant().getId(), item.getChoices())) {
                    if (!part.available()) {
                        String option = KitService.optionLabel(part.variant());
                        throw new UnprocessableException(part.variant().getProduct().getName()
                                + (option == null ? "" : " (" + option + ")") + " in " + item.getProductNameSnapshot()
                                + " is no longer available. Please remove the kit from your cart and choose again.");
                    }
                }
            }
        }
        BigDecimal subtotal = refreshed.items().stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (expectedSubtotal != null && expectedSubtotal.compareTo(subtotal) != 0) {
            throw new PriceChangedException("Some prices changed while you were checking out. We've updated your"
                    + " cart: please look over the new total and place your order again.");
        }
    }

    @Transactional
    public CartDto addItem(String sessionId, UUID variantId, BigDecimal qty) {
        return addItem(sessionId, variantId, qty, null);
    }

    /**
     * {@code kitChoices} is only for a kit: what the shopper picked instead of what it comes with (the default variant's id
     * to another colour or size of the same product). The same kit with different picks is a different basket line.
     */
    @Transactional
    public CartDto addItem(String sessionId, UUID variantId, BigDecimal qty, Map<UUID, UUID> kitChoices) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        Cart cart = findOrCreateCart(sessionId);
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No product variant: " + variantId));
        requireSellable(variant);
        QuantityRules.requireSensible(variant.getProduct().getName(), variant, qty);

        // What was picked inside a kit is checked here and kept only where it differs from the kit as it comes.
        Map<UUID, UUID> picks = kitChoices == null || kitChoices.isEmpty()
                ? Map.of() : kitService.normalizeChoices(variantId, kitChoices);
        String choiceKey = KitChoices.keyOf(picks);
        CartItem item = cartItemRepository.findByCartIdAndVariantIdAndChoiceKey(cart.getId(), variantId, choiceKey)
                .orElseGet(() -> {
                    CartItem newItem = new CartItem();
                    newItem.setCart(cart);
                    newItem.setVariant(variant);
                    newItem.setQty(BigDecimal.ZERO);
                    newItem.setChoices(new HashMap<>(picks));
                    newItem.setChoiceKey(choiceKey);
                    return newItem;
                });
        // The whole line, not just this click: with 3 in stock and 2 already in the basket,
        // adding 2 more used to pass because each add was compared to stock on its own.
        checkCourtesyAvailability(variant, item.getQty().add(qty), item.getChoices());
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
        QuantityRules.requireSensible(item.getVariant().getProduct().getName(), item.getVariant(), qty);
        checkCourtesyAvailability(item.getVariant(), qty, item.getChoices());
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
     * wins), else the plain selling price (see EffectivePriceResolver); for a kit with picks, plus what the picks
     * add or take off. This snapshot is what checkout later trusts without re-resolving, so it must be right here,
     * not just at display time.
     */
    private void applyPriceForQty(CartItem item) {
        EffectivePriceResolver.EffectivePrice price = withPicks(item,
                priceResolver.resolve(item.getVariant(), promoCampaignService.getActiveOrNull(), item.getQty()));
        item.setUnitPriceSnapshot(price.effectivePrice());
        item.setWholesaleApplied(price.source() == EffectivePriceResolver.Source.WHOLESALE);
    }

    /**
     * A kit's price as it comes, moved by the shopper's picks: the quantity of each product they changed times the
     * difference between the selling price of what they picked and of what the kit comes with. Never below zero.
     */
    private EffectivePriceResolver.EffectivePrice withPicks(CartItem item, EffectivePriceResolver.EffectivePrice price) {
        if (!item.getVariant().isKit() || item.getChoices().isEmpty()) {
            return price;
        }
        BigDecimal delta = kitService.priceDelta(item.getVariant().getId(), item.getChoices());
        if (delta.signum() == 0) {
            return price;
        }
        return new EffectivePriceResolver.EffectivePrice(price.originalPrice().add(delta).max(BigDecimal.ZERO),
                price.effectivePrice().add(delta).max(BigDecimal.ZERO), price.dealEndsAt(), price.source());
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
        if (variant.getProduct().isMockData()) {
            throw new UnprocessableException(demoProductMessage(variant.getProduct().getName()));
        }
        if (!isPurchasable(variant)) {
            throw new IllegalArgumentException("This item is no longer available");
        }
    }

    /**
     * Sellable, published, and REAL. Demo-catalogue products (see MockDataSeederService) are never for sale,
     * whether demo mode is on or off: they exist to show the shop populated, and an order for one would take a
     * real payment for something that does not exist. Real products stay purchasable whatever the demo state is.
     */
    private static boolean isPurchasable(ProductVariant variant) {
        return variant.isSellable() && variant.getProduct().isPublished() && !variant.getProduct().isDeleted()
                && !variant.getProduct().isMockData();
    }

    /** What a shopper is told when a demo-catalogue product is in their basket or they try to add one. */
    public static String demoProductMessage(String productName) {
        return productName + " is a demo product and can't be ordered.";
    }

    /**
     * What a shopper is told when the basket line asks for more than is in stock. The exact stock
     * figure is only shown when it is low (Odoo's website_sale_stock does the same with its
     * available_threshold, default 5) — anything higher tells a competitor how deep the shelf is,
     * and the old message also printed the internal SKU.
     */
    private static final BigDecimal LOW_STOCK_THRESHOLD = new BigDecimal("5");

    private void checkCourtesyAvailability(ProductVariant variant, BigDecimal requestedQty, Map<UUID, UUID> picks) {
        // A kit holds no StockBalance of its own — what it can sell is however many whole kits its components
        // can still make up (with the shopper's picks, from the shelves of the colours and sizes they chose).
        // Checkout still reserves each component and is the final word.
        BigDecimal available;
        if (!variant.isKit()) {
            available = availabilityService.available(variant.getId());
        } else if (picks.isEmpty()) {
            available = kitAvailability.wholeKitsAvailable(java.util.List.of(variant.getId()))
                    .getOrDefault(variant.getId(), BigDecimal.ZERO);
        } else {
            available = kitAvailability.wholeKitsFrom(partsOf(kitService.selectionFor(variant.getId(), picks)));
        }
        if (available.compareTo(requestedQty) >= 0) {
            return;
        }
        String name = variant.displayLabel();
        if (available.signum() <= 0) {
            throw new IllegalArgumentException(name + " is out of stock");
        }
        if (available.compareTo(LOW_STOCK_THRESHOLD) <= 0) {
            throw new IllegalArgumentException(
                    "Only " + available.stripTrailingZeros().toPlainString() + " of " + name + " left");
        }
        throw new IllegalArgumentException("That quantity of " + name + " isn't available right now");
    }

    /** The parts a shelf is counted against: for each product of the kit, the variant that will be packed. */
    private static List<KitAvailabilityService.Part> partsOf(List<KitService.SelectedPart> selection) {
        return selection.stream().map(p -> new KitAvailabilityService.Part(p.variant(), p.qtyPerKit())).toList();
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
        // What is inside each kit line, with the shopper's picks applied (empty for every other line).
        Map<UUID, List<KitService.SelectedPart>> selections = new HashMap<>();
        for (CartItem item : refreshed.items()) {
            if (item.getVariant().isKit()) {
                selections.put(item.getId(), kitService.selectionFor(item.getVariant().getId(), item.getChoices()));
            }
        }
        Map<UUID, BigDecimal> stockLeft = stockLeftFor(refreshed.items(), selections);
        // One photo lookup for everything shown from a kit: the products inside it and the kit's own thumbnail.
        Map<UUID, ProductVariant> shown = new HashMap<>();
        selections.values().forEach(parts -> parts.forEach(p -> shown.putIfAbsent(p.variant().getId(), p.variant())));
        refreshed.items().stream().filter(i -> i.getVariant().isKit())
                .forEach(i -> shown.putIfAbsent(i.getVariant().getId(), i.getVariant()));
        Map<UUID, String> covers = variantCovers.urlsFor(shown.values());
        List<CartItemDto> items = refreshed.items().stream()
                .map(item -> {
                    List<KitService.SelectedPart> parts = selections.getOrDefault(item.getId(), List.of());
                    boolean available = isPurchasable(item.getVariant())
                            && parts.stream().allMatch(KitService.SelectedPart::available);
                    return CartItemDto.from(item, available,
                            stockLeft.getOrDefault(item.getId(), BigDecimal.ZERO),
                            refreshed.changedFrom().get(item.getId()), kitPartDtos(parts, covers),
                            item.getVariant().isKit() ? covers.get(item.getVariant().getId()) : null);
                })
                .toList();
        return CartDto.from(cart, items);
    }

    private static List<KitPartDto> kitPartDtos(List<KitService.SelectedPart> parts, Map<UUID, String> covers) {
        return parts.stream().map(p -> new KitPartDto(
                p.variant().getId(), p.defaultVariant().getId(), p.variant().getProduct().getName(),
                KitService.optionLabel(p.variant()), p.variant().getOptionColor(), p.variant().getOptionSize(),
                p.variant().getSwatchHex(), p.qtyPerKit(), covers.get(p.variant().getId()),
                p.changed(), p.available())).toList();
    }

    /**
     * What each line can still be sold, keyed by the BASKET LINE (the same kit with different picks is several lines):
     * its own stock, or for a kit the whole kits its components can make, from the shelves of the variants picked.
     * Lines draw on one shelf, so they are served in the order they were added: a line's figure is what is left AFTER
     * the lines before it took their share. A kit and a loose unit of one of its parts, or two kits sharing a part,
     * therefore cannot both look fine while together asking for more than there is (checkout adds up what every line
     * needs from the same part before it reserves, and would refuse an order the cart had just called fine). The line
     * that does not fit is the one flagged.
     */
    private Map<UUID, BigDecimal> stockLeftFor(List<CartItem> items, Map<UUID, List<KitService.SelectedPart>> selections) {
        List<CartItem> inOrder = items.stream()
                .sorted(java.util.Comparator.comparing(CartItem::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        // A kit taken as it comes counts its own parts; one with picks counts the variants picked.
        List<UUID> plainKitIds = inOrder.stream()
                .filter(l -> l.getVariant().isKit() && l.getChoices().isEmpty())
                .map(l -> l.getVariant().getId()).distinct().toList();
        Map<UUID, List<KitAvailabilityService.Part>> defaultParts = kitAvailability.partsOf(plainKitIds);
        Map<UUID, List<KitAvailabilityService.Part>> partsByLine = new HashMap<>();
        for (CartItem line : inOrder) {
            if (!line.getVariant().isKit()) {
                continue;
            }
            partsByLine.put(line.getId(), line.getChoices().isEmpty()
                    ? defaultParts.getOrDefault(line.getVariant().getId(), List.of())
                    : partsOf(selections.getOrDefault(line.getId(), List.of())));
        }

        // The real variants whose shelf matters: a plain line's own, a kit's parts.
        java.util.Set<UUID> shelfIds = new java.util.HashSet<>();
        for (CartItem line : inOrder) {
            if (line.getVariant().isKit()) {
                partsByLine.getOrDefault(line.getId(), List.of()).forEach(p -> shelfIds.add(p.variant().getId()));
            } else {
                shelfIds.add(line.getVariant().getId());
            }
        }
        Map<UUID, BigDecimal> remaining = new HashMap<>(availabilityService.available(shelfIds));

        Map<UUID, BigDecimal> result = new HashMap<>();
        for (CartItem line : inOrder) {
            ProductVariant v = line.getVariant();
            if (!v.isKit()) {
                BigDecimal left = remaining.getOrDefault(v.getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
                result.put(line.getId(), left);
                remaining.put(v.getId(), left.subtract(line.getQty().min(left)));
                continue;
            }
            List<KitAvailabilityService.Part> parts = partsByLine.getOrDefault(line.getId(), List.of());
            BigDecimal whole = null;
            for (KitAvailabilityService.Part part : parts) {
                BigDecimal have = remaining.getOrDefault(part.variant().getId(), BigDecimal.ZERO).max(BigDecimal.ZERO);
                BigDecimal kitsFromThis = have.divide(part.qty(), 0, java.math.RoundingMode.DOWN);
                whole = whole == null ? kitsFromThis : whole.min(kitsFromThis);
            }
            BigDecimal left = whole == null ? BigDecimal.ZERO : whole;
            result.put(line.getId(), left);
            BigDecimal taken = line.getQty().min(left);
            for (KitAvailabilityService.Part part : parts) {
                BigDecimal id = remaining.getOrDefault(part.variant().getId(), BigDecimal.ZERO);
                remaining.put(part.variant().getId(), id.subtract(taken.multiply(part.qty())).max(BigDecimal.ZERO));
            }
        }
        return result;
    }

    private record Refreshed(List<CartItem> items, Map<UUID, BigDecimal> changedFrom) {}

    private Refreshed refreshPrices(Cart cart) {
        // In the order they were added, so a line never moves when its quantity or price is updated.
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .sorted(CartItem.IN_ORDER_ADDED).toList();
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
     * Today's price for a line (a kit with picks: its price plus what the picks make of it). A product that can no longer
     * be bought (unpublished, deleted, no price) keeps the price it had: there is nothing to look up, and the line is
     * reported as unavailable instead.
     */
    private EffectivePriceResolver.EffectivePrice currentPrice(CartItem item, PromoCampaign campaign) {
        ProductVariant variant = item.getVariant();
        if (!isPurchasable(variant)) {
            return new EffectivePriceResolver.EffectivePrice(item.getUnitPriceSnapshot(), item.getUnitPriceSnapshot(),
                    null, EffectivePriceResolver.Source.NONE);
        }
        return withPicks(item, priceResolver.resolve(variant, campaign, item.getQty()));
    }
}
