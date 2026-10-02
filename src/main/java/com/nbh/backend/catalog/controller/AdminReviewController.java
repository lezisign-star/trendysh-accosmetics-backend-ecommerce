package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.ProductReviewDto;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.service.ProductReviewService;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Review moderation — gated the same way as the rest of catalogue management. */
@RestController
@RequestMapping("/api/v1/admin/reviews")
public class AdminReviewController {

    private final ProductReviewService reviewService;

    public AdminReviewController(ProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** Every customer review (newest first), optionally just one status — e.g. PENDING for the moderation queue. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE') or hasAuthority('PERM_ORDER_VIEW')")
    @org.springframework.web.bind.annotation.GetMapping
    public org.springframework.data.domain.Page<com.nbh.backend.catalog.dto.AdminReviewDto> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) ProductReview.ReviewStatus status,
            @org.springframework.data.web.PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
        return reviewService.listForStaff(status, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/approve")
    public ProductReviewDto approve(@PathVariable UUID id, Authentication authentication) {
        return reviewService.moderate(id, ProductReview.ReviewStatus.APPROVED, staffId(authentication));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/reject")
    public ProductReviewDto reject(@PathVariable UUID id, Authentication authentication) {
        return reviewService.moderate(id, ProductReview.ReviewStatus.REJECTED, staffId(authentication));
    }

    private UUID staffId(Authentication authentication) {
        return ((StaffUser) authentication.getPrincipal()).getId();
    }
}
