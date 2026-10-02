package com.nbh.backend.imagecleanup;

import com.nbh.backend.imagecleanup.ImageCleanupService.BatchDto;
import com.nbh.backend.imagecleanup.ImageCleanupService.StatusDto;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin surface for AI photo cleanup. Running one costs real money, so it needs product-management rights. */
@RestController
@RequestMapping("/api/v1/admin/image-cleanup")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class ImageCleanupController {

    public record RunRequest(@Min(1) @Max(200) int limit, @Min(1) @Max(6) int imagesPerProduct) {}

    public record ProductRunRequest(@Min(1) @Max(6) int imagesPerProduct) {}

    private final ImageCleanupService service;

    public ImageCleanupController(ImageCleanupService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public StatusDto status() {
        return service.status();
    }

    @GetMapping("/batches")
    public List<BatchDto> batches() {
        return service.recent();
    }

    @GetMapping("/batches/{id}")
    public BatchDto batch(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/run")
    public BatchDto run(@Valid @RequestBody RunRequest request, @AuthenticationPrincipal StaffUser staff) {
        return service.startBatch(request.limit(), request.imagesPerProduct(), staff.getId());
    }

    @PostMapping("/run/{productId}")
    public BatchDto runForProduct(@PathVariable UUID productId, @Valid @RequestBody ProductRunRequest request,
                                  @AuthenticationPrincipal StaffUser staff) {
        return service.startForProduct(productId, request.imagesPerProduct(), staff.getId());
    }

    @DeleteMapping("/batches/{id}")
    public BatchDto undo(@PathVariable UUID id) {
        return service.undo(id);
    }
}
