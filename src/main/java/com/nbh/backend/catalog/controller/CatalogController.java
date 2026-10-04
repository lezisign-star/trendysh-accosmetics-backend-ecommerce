package com.nbh.backend.catalog.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.dto.*;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.service.CatalogPageCache;
import com.nbh.backend.catalog.service.CatalogService;
import com.nbh.backend.catalog.service.ProductReviewService;
import com.nbh.backend.catalog.service.PromoCampaignService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.UncheckedIOException;
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
    private final CatalogPageCache pageCache;
    private final ObjectMapper json;
    private final Clock clock;

    public CatalogController(CatalogService catalogService, ProductReviewService reviewService,
                              PromoCampaignService promoCampaignService, CatalogPageCache pageCache,
                              ObjectMapper json, Clock clock) {
        this.catalogService = catalogService;
        this.reviewService = reviewService;
        this.promoCampaignService = promoCampaignService;
        this.pageCache = pageCache;
        this.json = json;
        this.clock = clock;
    }

    /**
     * The storefront's browse grid, one card per sellable variant (Phase 3, 2026-09-24).
     *
     * <p>Plain browsing goes through {@link CatalogPageCache}: the JSON is built once per burst of identical
     * requests and carries a weak ETag, so a browser that already has these exact bytes gets a bodiless
     * {@code 304} when its live-update refresh finds nothing changed. {@code Cache-Control: no-cache} is what
     * makes that work — without it Spring Security's default {@code no-store} stops the browser keeping the
     * response to compare against.
     *
     * <p>Only requests whose possible inputs are few are cached ({@link #isCacheable}); everything else is
     * answered exactly as before. That keeps the cache's memory and its key space bounded however many odd
     * URLs anonymous visitors send: a free-text search, any {@code sort} (the query fixes its own order, so
     * the storefront never sends one), a page bigger than the 500 cards the storefront asks for, a far-off
     * page number, or an absurdly long category.
     */
    @GetMapping("/variants")
    public ResponseEntity<?> browseVariants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean deal,
            @RequestParam(required = false) Boolean popular,
            @RequestParam(name = "new", required = false) Boolean newArrival,
            @PageableDefault(size = 48) Pageable pageable) {
        if (!isCacheable(q, category, pageable)) {
            return ResponseEntity.ok(catalogService.browseCards(q, category, deal, popular, newArrival, pageable));
        }
        String key = String.join("|", String.valueOf(category), String.valueOf(deal), String.valueOf(popular),
                String.valueOf(newArrival), String.valueOf(pageable.getPageNumber()),
                String.valueOf(pageable.getPageSize()));
        CatalogPageCache.Cached page = pageCache.get(key, () ->
                serialise(catalogService.browseCards(null, category, deal, popular, newArrival, pageable)));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noCache())
                .eTag(page.etag())
                .body(page.body());
    }

    /** The largest page the storefront asks for (its catalogue loader fetches in chunks of this many). */
    static final int MAX_CACHED_PAGE_SIZE = 500;

    static boolean isCacheable(String q, String category, Pageable pageable) {
        return (q == null || q.isBlank())
                && pageable.getSort().isUnsorted()
                && pageable.getPageSize() <= MAX_CACHED_PAGE_SIZE
                && pageable.getPageNumber() < 1_000
                && (category == null || category.length() <= 80);
    }

    private byte[] serialise(Page<CatalogVariantCardDto> page) {
        try {
            return json.writeValueAsBytes(page);
        } catch (JsonProcessingException e) {
            // Not an IllegalStateException: the exception handler maps that to a 422 "your request was wrong".
            throw new UncheckedIOException("Couldn't write the catalogue page", e);
        }
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
