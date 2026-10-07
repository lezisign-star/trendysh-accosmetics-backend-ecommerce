package com.nbh.backend.catalog.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Moves a photo to the option that owns it. */
public record AssignImageRequest(@NotNull UUID variantId) {}
