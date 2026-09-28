package com.nbh.backend.kits.dto;

import com.nbh.backend.kits.entity.Profession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class ProfessionDtos {

    private ProfessionDtos() {}

    /** Max lengths match the profession table's VARCHAR(100) columns. */
    public record ProfessionRequest(
            @NotBlank @Size(max = 100) String label,
            @NotBlank @Size(max = 100) String slug) {}

    public record ProfessionDto(UUID id, String slug, String label) {
        public static ProfessionDto from(Profession p) {
            return new ProfessionDto(p.getId(), p.getSlug(), p.getLabel());
        }
    }
}
