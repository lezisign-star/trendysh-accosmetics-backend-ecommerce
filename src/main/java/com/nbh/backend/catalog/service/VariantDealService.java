package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * A variant's own flash deal — a sale price with its own deadline, set or cleared as one unit (a
 * deal price with no end time, or vice versa, is meaningless). See {@link EffectivePriceResolver}
 * for how this interacts with an active site-wide campaign (this always wins when both apply).
 */
@Service
public class VariantDealService {

    private final ProductVariantRepository variantRepository;
    private final Clock clock;

    public VariantDealService(ProductVariantRepository variantRepository, Clock clock) {
        this.variantRepository = variantRepository;
        this.clock = clock;
    }

    @Transactional
    public ProductVariant setDeal(UUID productId, UUID variantId, BigDecimal dealPrice, Instant dealEndsAt) {
        ProductVariant variant = load(productId, variantId);
        if (dealPrice == null || dealPrice.signum() <= 0) {
            throw new IllegalArgumentException("Deal price must be greater than zero");
        }
        if (dealPrice.compareTo(variant.getSellingPrice()) >= 0) {
            throw new IllegalArgumentException("Deal price must be less than the regular selling price ("
                    + variant.getSellingPrice() + ")");
        }
        if (dealEndsAt == null || !dealEndsAt.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("Deal end time must be in the future");
        }
        variant.setDealPrice(dealPrice);
        variant.setDealEndsAt(dealEndsAt);
        return variantRepository.save(variant);
    }

    @Transactional
    public ProductVariant clearDeal(UUID productId, UUID variantId) {
        ProductVariant variant = load(productId, variantId);
        variant.setDealPrice(null);
        variant.setDealEndsAt(null);
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
