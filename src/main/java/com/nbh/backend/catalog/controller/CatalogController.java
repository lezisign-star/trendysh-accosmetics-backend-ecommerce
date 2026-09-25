package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.*;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.service.CatalogService;
import com.nbh.backend.catalog.service.ProductReviewService;
import com.nbh.backend.catalog.service.PromoCampaignService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
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
    private final PromoCampaignService promoCampaignService;
    private final Clock clock;

    public CatalogController(CatalogService catalogService, ProductReviewService reviewService,
                              PromoCampaignService promoCampaignService, Clock clock) {
        this.catalogService = catalogService;
        this.reviewService = reviewService;
        this.promoCampaignService = promoCampaignService;
        this.clock = clock;
    }

    /** The storefront's browse grid, one card per sellable variant (Phase 3, 2026-09-24). */
    @GetMapping("/variants")
    public Page<CatalogVariantCardDto> browseVariants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean deal,
            @RequestParam(required = false) Boolean popular,
            @RequestParam(name = "new", required = false) Boolean newArrival,
            @PageableDefault(size = 48) Pageable pageable) {
        return catalogService.browseCards(q, category, deal, popular, newArrival, pageable);
    }

    @GetMapping("/variants/{variantId}")
    public CatalogVariantDetailDto variantDetail(@PathVariable UUID variantId) {
        return catalogService.variantDetail(variantId);
    }

    /** The one currently-active site-wide campaign, or 204 when none is running — drives the compact top banner. */
    @GetMapping("/promo-campaign")
    @Transactional
    public ResponseEntity<PromoCampaignDto> activePromoCampaign() {
        PromoCampaign active = promoCampaignService.getActiveOrNull();
        return active == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(PromoCampaignDto.from(active, Instant.now(clock)));
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
