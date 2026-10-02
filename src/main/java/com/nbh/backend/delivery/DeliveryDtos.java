package com.nbh.backend.delivery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeliveryDtos {

    private DeliveryDtos() {}

    public record PriceDto(DeliveryMethod method, BigDecimal priceMin, BigDecimal priceMax, String etaText, String notes) {
        static PriceDto from(DeliveryPlacePrice p) {
            return new PriceDto(p.getMethod(), p.getPriceMin(), p.getPriceMax(), p.getEtaText(), p.getNotes());
        }
    }

    public record PlaceDto(UUID id, String name, String region, String notes, boolean active, List<PriceDto> prices) {
        static PlaceDto from(DeliveryPlace p) {
            return new PlaceDto(p.getId(), p.getName(), p.getRegion(), p.getNotes(), p.isActive(),
                    p.getPrices().stream()
                            .sorted(java.util.Comparator.comparing(DeliveryPlacePrice::getMethod))
                            .map(PriceDto::from).toList());
        }
    }

    public record PriceRequest(@NotNull DeliveryMethod method,
                               @NotNull @DecimalMin("0") BigDecimal priceMin,
                               @NotNull @DecimalMin("0") BigDecimal priceMax,
                               @Size(max = 100) String etaText,
                               @Size(max = 300) String notes) {}

    public record SavePlaceRequest(@NotBlank @Size(max = 150) String name,
                                   @Size(max = 100) String region,
                                   String notes,
                                   boolean active,
                                   @NotNull @Valid List<PriceRequest> prices) {}

    public record SuggestRequest(@NotBlank @Size(max = 150) String name,
                                 @Size(max = 1000) String details,
                                 @Size(max = 100) String contact) {}

    public record SuggestionDto(UUID id, String name, String details, String contact,
                                DeliveryPlaceSuggestion.Status status, String resolvedNote, UUID placeId,
                                Instant createdAt) {
        static SuggestionDto from(DeliveryPlaceSuggestion s) {
            return new SuggestionDto(s.getId(), s.getName(), s.getDetails(), s.getContact(), s.getStatus(),
                    s.getResolvedNote(), s.getPlaceId(), s.getCreatedAt());
        }
    }

    public record ResolveRequest(@Size(max = 300) String note, UUID placeId) {}
}
