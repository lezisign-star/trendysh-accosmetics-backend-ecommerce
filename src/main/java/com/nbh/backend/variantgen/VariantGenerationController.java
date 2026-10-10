package com.nbh.backend.variantgen;

import com.nbh.backend.imagecleanup.ImageCleanupService.BatchDto;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Generate variants" from a finished product. Needs product-management rights like the photo studio (it spends Gemini
 * quota). Every field except the product is optional: colours and sizes may be left for Gemini to read out of the prompt.
 */
@RestController
@RequestMapping("/api/v1/admin/variant-generation")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class VariantGenerationController {

    public record RunRequest(UUID productId, UUID baseVariantId, @Size(max = 150) List<@Size(max = 60) String> colors,
                             @Size(max = 150) List<@Size(max = 60) String> sizes, @Size(max = 2000) String prompt,
                             BigDecimal price, @Size(max = 80) String baseOption, boolean approveFirst) {}

    public record RetryRequest(@Size(max = 100) List<UUID> variantIds, boolean approveFirst) {}

    private final VariantGenerationService service;

    public VariantGenerationController(VariantGenerationService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public BatchDto run(@Valid @RequestBody RunRequest request, @AuthenticationPrincipal StaffUser staff) {
        if (request.productId() == null) {
            throw new com.nbh.backend.common.exception.UnprocessableException("Say which product to make variants of.");
        }
        return service.start(new VariantGenerationService.Request(request.productId(), request.baseVariantId(),
                request.colors(), request.sizes(), request.prompt(), request.price(), request.baseOption(),
                request.approveFirst()), staff.getId());
    }

    @GetMapping("/runs/{batchId}")
    public VariantGenerationService.RunDto runStatus(@PathVariable UUID batchId) {
        return service.run(batchId);
    }

    /** Approve-first runs: reveal the run's variants to shoppers. */
    @PostMapping("/runs/{batchId}/publish")
    public Map<String, Integer> publish(@PathVariable UUID batchId) {
        return Map.of("published", service.publishRun(batchId));
    }

    @GetMapping("/needs-images")
    public List<VariantGenerationService.VariantRow> needsImages() {
        return service.needingImages();
    }

    @PostMapping("/needs-images/run")
    public BatchDto retry(@Valid @RequestBody(required = false) RetryRequest request,
                          @AuthenticationPrincipal StaffUser staff) {
        return service.retryPhotos(request == null ? null : request.variantIds(),
                request != null && request.approveFirst(), staff.getId());
    }
}
