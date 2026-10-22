package com.nbh.backend.kits.dto;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.kits.entity.Kit;
import com.nbh.backend.kits.entity.KitComponent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KitDtos {

    private KitDtos() {}

    /**
     * {@code customerCanChange} is whether a shopper may pick another colour or size of this product inside the kit;
     * left out (an older client) it means yes, the way it is for every kit made before the switch existed.
     */
    public record ComponentInput(@NotNull UUID variantId, @NotBlank String qty, Boolean customerCanChange) {
        public ComponentInput(UUID variantId, String qty) {
            this(variantId, qty, null);
        }

        public boolean canChange() {
            return customerCanChange == null || customerCanChange;
        }
    }

    /**
     * {@code finalPrice} is the one number the backend needs — whichever of the admin UI's three
     * input modes (target price / percent off / flat amount off) produced it is resolved client-side;
     * the backend only ever stores the resulting fixed price. {@code professionId} points at an
     * existing {@code Profession} — creating a new one (the kit form's inline "+ New") is a separate
     * {@code POST /admin/professions} call the frontend makes first, exactly like "+ New supplier"
     * on the purchase-order form.
     */
    public record SaveKitRequest(
            @NotBlank @Size(max = 250) String name,
            @NotNull UUID professionId,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 500) String description,
            @NotBlank String finalPrice,
            @NotEmpty @Valid List<ComponentInput> components) {}

    /** A component variant counts as "available" when it's still purchasable on its own — not
     *  deleted, active, priced, and its product still published and not deleted. Mirrors
     *  {@code CartService.isPurchasable}, duplicated here rather than shared, the same isolation
     *  this package already keeps for {@code ProductService.generateUniqueSlug}. An unavailable
     *  component is auto-excluded from what a customer is shown/sold — see {@code KitService}. */
    public static boolean isComponentAvailable(ProductVariant v) {
        return v.isSellable() && v.getProduct().isPublished() && !v.getProduct().isDeleted();
    }

    public record ComponentDto(UUID variantId, String sku, String productName, String variantName,
                                BigDecimal qty, BigDecimal currentUnitPrice, BigDecimal lineTotal,
                                boolean available,
                                /** Whether a shopper may pick another colour or size of this product inside the kit. */
                                boolean customerCanChange) {

        public static ComponentDto from(KitComponent c) {
            var v = c.getComponentVariant();
            BigDecimal price = v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice();
            return new ComponentDto(v.getId(), v.getSku(), v.getProduct().getName(), v.getVariantName(),
                    c.getQty(), price, price.multiply(c.getQty()), isComponentAvailable(v), c.isCustomerCanChange());
        }
    }

    /**
     * {@code sumOfComponents}/{@code discountPercent} are computed over the kit's FULL defined
     * composition (as the admin set it up), unaffected by any component currently being
     * unavailable — {@code hasUnavailableComponent} (and each line's own {@code available}) carry
     * that warning separately, since collapsing it into these figures would make "what's the deal
     * really worth" silently disagree with what the admin actually configured.
     */
    public record KitAdminDto(UUID id, UUID variantId, UUID productId, String sku, String name,
                               UUID professionId, String professionLabel, String professionSlug,
                               String description,
                               UUID categoryId, String categoryName, boolean isActive,
                               BigDecimal packagePrice, BigDecimal sumOfComponents, BigDecimal discountPercent,
                               boolean hasUnavailableComponent,
                               Instant updatedAt, List<ComponentDto> components,
                               /** The kit's own thumbnail (the professional at work): its variant's cover photo; null until one is added. */
                               String imageUrl) {

        public static KitAdminDto from(Kit k) {
            return from(k, null);
        }

        public static KitAdminDto from(Kit k, String imageUrl) {
            var variant = k.getVariant();
            var product = variant.getProduct();
            var profession = k.getProfession();
            // In the order staff set them (a kit's products are updated in place, so the list is not always in that order).
            List<ComponentDto> components = k.getComponents().stream()
                    .sorted(java.util.Comparator.comparingInt(KitComponent::getLineNo)).map(ComponentDto::from).toList();
            BigDecimal sum = components.stream().map(ComponentDto::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal packagePrice = variant.getSellingPrice() == null ? BigDecimal.ZERO : variant.getSellingPrice();
            BigDecimal discountPercent = sum.signum() <= 0
                    ? BigDecimal.ZERO
                    : sum.subtract(packagePrice).multiply(BigDecimal.valueOf(100))
                            .divide(sum, 1, RoundingMode.HALF_UP);
            boolean hasUnavailableComponent = components.stream().anyMatch(c -> !c.available());
            var category = product.getCategories().isEmpty() ? null : product.getCategories().get(0);
            return new KitAdminDto(k.getId(), variant.getId(), product.getId(), variant.getSku(), product.getName(),
                    profession.getId(), profession.getLabel(), profession.getSlug(), k.getDescription(),
                    category != null ? category.getId() : null, category != null ? category.getName() : null,
                    variant.isActive(), packagePrice, sum, discountPercent, hasUnavailableComponent,
                    k.getUpdatedAt(), components, imageUrl);
        }
    }
}
