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

    /**
     * What staff change on the Professions screen (owner, 2026-10-24). The link ({@code slug}) is not among them: it stays
     * as it was made, so links shared and pages search engines know keep working after a rename.
     */
    public record UpdateProfessionRequest(
            @NotBlank @Size(max = 100) String label,
            @Size(max = 600) String description,
            @Size(max = 1000) String imageUrl) {}

    /** {@code kitCount} counts every kit listed under the trade, as its main one or as an extra one. */
    public record ProfessionDto(UUID id, String slug, String label, String description, String imageUrl, long kitCount) {
        public static ProfessionDto from(Profession p, long kitCount) {
            return new ProfessionDto(p.getId(), p.getSlug(), p.getLabel(), p.getDescription(), p.getImageUrl(), kitCount);
        }
    }
}
