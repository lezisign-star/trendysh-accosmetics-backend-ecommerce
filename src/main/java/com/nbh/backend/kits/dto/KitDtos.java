package com.nbh.backend.kits.dto;

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

    public record ComponentInput(@NotNull UUID variantId, @NotBlank String qty) {}

    /**
     * {@code finalPrice} is the one number the backend needs — whichever of the admin UI's three
     * input modes (target price / percent off / flat amount off) produced it is resolved client-side;
     * the backend only ever stores the resulting fixed price. {@code professionId} points at an
     * existing {@code Profession} — creating a new one (the kit form's inline "+ New") is a separate
     * {@code POST /admin/professions} call the frontend makes first, exactly like "+ New supplier"
     * on the purchase-order form.
     */
    public record SaveKitRequest(
            @NotBlank String name,
            @NotNull UUID professionId,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 500) String description,
            @NotBlank String finalPrice,
            @NotEmpty @Valid List<ComponentInput> components) {}

    public record ComponentDto(UUID variantId, String sku, String productName, String variantName,
                                BigDecimal qty, BigDecimal currentUnitPrice, BigDecimal lineTotal) {

        public static ComponentDto from(KitComponent c) {
            var v = c.getComponentVariant();
            BigDecimal price = v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice();
            return new ComponentDto(v.getId(), v.getSku(), v.getProduct().getName(), v.getVariantName(),
                    c.getQty(), price, price.multiply(c.getQty()));
        }
    }

    public record KitAdminDto(UUID id, UUID variantId, UUID productId, String sku, String name,
                               UUID professionId, String professionLabel, String professionSlug,
                               String description,
                               UUID categoryId, String categoryName, boolean isActive,
                               BigDecimal packagePrice, BigDecimal sumOfComponents, BigDecimal discountPercent,
                               Instant updatedAt, List<ComponentDto> components) {

        public static KitAdminDto from(Kit k) {
            var variant = k.getVariant();
            var product = variant.getProduct();
            var profession = k.getProfession();
            List<ComponentDto> components = k.getComponents().stream().map(ComponentDto::from).toList();
            BigDecimal sum = components.stream().map(ComponentDto::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal packagePrice = variant.getSellingPrice() == null ? BigDecimal.ZERO : variant.getSellingPrice();
            BigDecimal discountPercent = sum.signum() <= 0
                    ? BigDecimal.ZERO
                    : sum.subtract(packagePrice).multiply(BigDecimal.valueOf(100))
                            .divide(sum, 1, RoundingMode.HALF_UP);
            var category = product.getCategories().isEmpty() ? null : product.getCategories().get(0);
            return new KitAdminDto(k.getId(), variant.getId(), product.getId(), variant.getSku(), product.getName(),
                    profession.getId(), profession.getLabel(), profession.getSlug(), k.getDescription(),
                    category != null ? category.getId() : null, category != null ? category.getName() : null,
                    variant.isActive(), packagePrice, sum, discountPercent, k.getUpdatedAt(), components);
        }
    }
}
