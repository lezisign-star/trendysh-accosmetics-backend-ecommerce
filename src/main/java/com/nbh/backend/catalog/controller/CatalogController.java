package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.*;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.service.CatalogService;
import com.nbh.backend.catalog.service.ProductReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The public storefront read API — no authentication, deliberately separate
 * from {@link ProductController} (staff-only catalogue management). Every
 * response here only ever reflects published, non-deleted products; see
 * {@link CatalogService}'s javadoc.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogService catalogService;
    private final ProductReviewService reviewService;

    public CatalogController(CatalogService catalogService, ProductReviewService reviewService) {
        this.catalogService = catalogService;
        this.reviewService = reviewService;
    }

    @GetMapping("/products")
    public Page<CatalogProductSummaryDto> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @PageableDefault(size = 24) Pageable pageable) {
        return catalogService.browse(q, category, pageable);
    }

    @GetMapping("/products/{slug}")
    public CatalogProductDetailDto detail(@PathVariable String slug) {
        return catalogService.getDetail(slug);
    }

    @GetMapping("/products/{id}/related")
    public List<CatalogProductSummaryDto> related(
            @PathVariable UUID id, @RequestParam(defaultValue = "4") int limit) {
        return catalogService.related(id, Math.min(limit, 20));
    }

    @PostMapping("/products/{id}/view")
    public void recordView(@PathVariable UUID id, @RequestParam(required = false) UUID variantId) {
        catalogService.recordView(id, variantId);
    }

    @GetMapping("/categories")
    public List<CatalogCategoryDto> categories() {
        return catalogService.listCategories();
    }

    @GetMapping("/products/{id}/reviews")
    public Page<ProductReviewDto> reviews(@PathVariable UUID id, @PageableDefault(size = 10) Pageable pageable) {
        return reviewService.listApproved(id, pageable);
    }

    @PostMapping("/products/{id}/reviews")
    public ProductReviewDto submitReview(@PathVariable UUID id, @Valid @RequestBody SubmitReviewRequest request) {
        return reviewService.submit(id, request);
    }
}
