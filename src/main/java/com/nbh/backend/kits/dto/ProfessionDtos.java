package com.nbh.backend.kits.dto;

import com.nbh.backend.kits.entity.Profession;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class ProfessionDtos {

    private ProfessionDtos() {}

    public record ProfessionRequest(@NotBlank String label, @NotBlank String slug) {}

    public record ProfessionDto(UUID id, String slug, String label) {
        public static ProfessionDto from(Profession p) {
            return new ProfessionDto(p.getId(), p.getSlug(), p.getLabel());
        }
    }
}
