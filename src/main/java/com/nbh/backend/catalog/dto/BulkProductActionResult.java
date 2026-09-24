package com.nbh.backend.catalog.dto;

import java.util.List;

public record BulkProductActionResult(int affectedProducts, int affectedVariants, List<String> warnings) {}
