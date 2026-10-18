package com.nbh.backend.catalog.dto;

import java.time.Instant;

/**
 * Renames a product and nothing else (the product editor's name autosave). The request carries no shop switch, no
 * variants and no other field, so it cannot change them: that is the point of having it next to the full Save.
 */
public record RenameProductRequest(
        /** The product's {@code updatedAt} the editor's form was loaded from; a mismatch means someone changed it first. Null skips the check. */
        Instant expectedUpdatedAt,
        String name
) {}
