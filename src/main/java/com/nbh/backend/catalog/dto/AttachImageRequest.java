package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code url} is the secure_url Cloudinary returned from the signed upload — see CloudinarySignatureService. */
public record AttachImageRequest(
        @NotBlank String url,
        String thumbnailUrl,
        UUID variantId,
        String altText,
        boolean isPrimary,
        Integer width,
        Integer height,
        Integer byteSize
) {}
