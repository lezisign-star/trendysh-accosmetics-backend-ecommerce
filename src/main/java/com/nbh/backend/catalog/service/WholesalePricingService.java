package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A variant's own wholesale tier — a minimum quantity and a per-unit price that applies to the
 * whole cart line once that quantity is reached, set or cleared as one unit (a min quantity with
 * no price, or vice versa, is meaningless). See {@link EffectivePriceResolver} for how this
 * interacts with an active flash deal or site-wide campaign (cheapest per-unit price wins).
 */
@Service
public class WholesalePricingService {

    private final ProductVariantRepository variantRepository;

    public WholesalePricingService(ProductVariantRepository variantRepository) {
        this.variantRepository = variantRepository;
    }

    @Transactional
    public ProductVariant setWholesalePrice(UUID productId, UUID variantId, int minQty, BigDecimal wholesalePrice) {
        ProductVariant variant = load(productId, variantId);
        if (wholesalePrice == null || wholesalePrice.signum() <= 0) {
            throw new IllegalArgumentException("Wholesale price must be greater than zero");
        }
        if (variant.getSellingPrice() == null) {
            throw new IllegalArgumentException("Set a price for this variant first");
        }
        if (wholesalePrice.compareTo(variant.getSellingPrice()) >= 0) {
            throw new IllegalArgumentException("Wholesale price must be less than the regular selling price ("
                    + variant.getSellingPrice() + ")");
        }
        if (minQty <= 1) {
            throw new IllegalArgumentException("Minimum quantity must be at least 2");
        }
        variant.setWholesaleMinQty(minQty);
        variant.setWholesalePrice(wholesalePrice);
        return variantRepository.save(variant);
    }

    @Transactional
    public ProductVariant clearWholesalePrice(UUID productId, UUID variantId) {
        ProductVariant variant = load(productId, variantId);
        variant.setWholesaleMinQty(null);
        variant.setWholesalePrice(null);
        return variantRepository.save(variant);
    }

    private ProductVariant load(UUID productId, UUID variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new EntityNotFoundException("No variant: " + variantId));
        if (!variant.getProduct().getId().equals(productId)) {
            throw new EntityNotFoundException("No variant " + variantId + " on product " + productId);
        }
        return variant;
    }
}
