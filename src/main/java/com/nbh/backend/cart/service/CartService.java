package com.nbh.backend.cart.service;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.CartItemDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.repository.StockBalanceRepository;
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
    private final BranchRepository branchRepository;
    private final StockLocationRepository locationRepository;
    private final StockBalanceRepository balanceRepository;

    public CartService(CartRepository cartRepository,
                        CartItemRepository cartItemRepository,
                        ProductVariantRepository variantRepository,
                        BranchRepository branchRepository,
                        StockLocationRepository locationRepository,
                        StockBalanceRepository balanceRepository) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.variantRepository = variantRepository;
        this.branchRepository = branchRepository;
        this.locationRepository = locationRepository;
        this.balanceRepository = balanceRepository;
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

        checkCourtesyAvailability(variant, qty);

        CartItem item = cartItemRepository.findByCartIdAndVariantId(cart.getId(), variantId)
                .orElseGet(() -> {
                    CartItem newItem = new CartItem();
                    newItem.setCart(cart);
                    newItem.setVariant(variant);
                    newItem.setQty(BigDecimal.ZERO);
                    return newItem;
                });
        item.setQty(item.getQty().add(qty));
        item.setUnitPriceSnapshot(variant.getSellingPrice());
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
        return cartRepository.findBySessionId(sessionId).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setSessionId(sessionId);
            return cartRepository.save(cart);
        });
    }

    private void checkCourtesyAvailability(ProductVariant variant, BigDecimal requestedQty) {
        Branch defaultBranch = branchRepository.findByIsDefaultOnlineFulfilmentTrue()
                .orElseThrow(() -> new IllegalStateException("No branch is configured as the default online-fulfilment branch"));
        StockLocation salesFloor = locationRepository
                .findByBranchIdAndLocationType(defaultBranch.getId(), LocationType.SALES_FLOOR)
                .orElseThrow();

        BigDecimal available = balanceRepository.findByVariantIdAndLocationId(variant.getId(), salesFloor.getId())
                .map(b -> b.getOnHandQty().subtract(b.getReservedQty()))
                .orElse(BigDecimal.ZERO);

        if (available.compareTo(requestedQty) < 0) {
            throw new IllegalArgumentException(
                    "Only " + available + " of " + variant.getSku() + " is currently available");
        }
    }

    private CartDto toDto(Cart cart) {
        List<CartItemDto> items = cartItemRepository.findByCartId(cart.getId()).stream()
                .map(CartItemDto::from)
                .toList();
        return CartDto.from(cart, items);
    }
}
