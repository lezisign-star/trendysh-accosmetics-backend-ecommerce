package com.nbh.backend.imagecleanup;

import com.nbh.backend.catalog.dto.AdminImageDto;
import com.nbh.backend.imagecleanup.ImageCleanupService.BatchDto;
import com.nbh.backend.imagecleanup.ImageCleanupService.RunOptions;
import com.nbh.backend.imagecleanup.ImageCleanupService.StatusDto;
import com.nbh.backend.imagecleanup.ImageDraftService.DraftDto;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface for AI photos. Making them costs real money, so it needs product-management rights. Every run states
 * whether its photos go straight into the shop ({@code publishNow}) or wait as drafts for staff to approve (the
 * default), which views to make and an optional note (owner, 2026-10-07).
 */
@RestController
@RequestMapping("/api/v1/admin/image-cleanup")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class ImageCleanupController {

    /** A bulk run over the next options that need photos. {@code views} wins over {@code imagesPerProduct}. */
    public record RunRequest(@Min(1) @Max(200) int limit, @Min(1) @Max(8) Integer imagesPerProduct,
                             @Size(max = 8) List<String> views,
                             @Size(max = 2000) String note, boolean publishNow) {
        RunOptions options() {
            return new RunOptions(views, imagesPerProduct, note, publishNow);
        }
    }

    /** One product: every option of it that needs photos. */
    public record ProductRunRequest(@Min(1) @Max(8) Integer imagesPerProduct, @Size(max = 8) List<String> views,
                                    @Size(max = 2000) String note, boolean publishNow) {
        RunOptions options() {
            return new RunOptions(views, imagesPerProduct, note, publishNow);
        }
    }

    /** One option, from one of its real photos (null = its main one). */
    public record OptionRunRequest(UUID sourceImageId, @Size(max = 8) List<String> views,
                                   @Size(max = 2000) String note, boolean publishNow) {
        RunOptions options() {
            return new RunOptions(views, null, note, publishNow);
        }
    }

    public record ApproveRequest(boolean makeMain) {}

    /** {@code note} null keeps the draft's own note; an empty string means no note. */
    public record RedoRequest(@Size(max = 2000) String note) {}

    private final ImageCleanupService service;
    private final ImageDraftService draftService;

    public ImageCleanupController(ImageCleanupService service, ImageDraftService draftService) {
        this.service = service;
        this.draftService = draftService;
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
        return service.startBatch(request.limit(), request.options(), staff.getId());
    }

    @PostMapping("/run/{productId}")
    public BatchDto runForProduct(@PathVariable UUID productId, @Valid @RequestBody ProductRunRequest request,
                                  @AuthenticationPrincipal StaffUser staff) {
        return service.startForProduct(productId, request.options(), staff.getId());
    }

    @PostMapping("/options/{variantId}/run")
    public BatchDto runForOption(@PathVariable UUID variantId, @Valid @RequestBody OptionRunRequest request,
                                 @AuthenticationPrincipal StaffUser staff) {
        return service.startForOption(variantId, request.sourceImageId(), request.options(), staff.getId());
    }

    @DeleteMapping("/batches/{id}")
    public BatchDto undo(@PathVariable UUID id) {
        return service.undo(id);
    }

    // ---- the review step ----

    @GetMapping("/drafts")
    public List<DraftDto> drafts(@RequestParam(required = false) UUID productId) {
        return draftService.list(productId);
    }

    @PostMapping("/drafts/{id}/approve")
    public AdminImageDto approve(@PathVariable UUID id, @RequestBody(required = false) ApproveRequest request) {
        return draftService.approve(id, request != null && request.makeMain());
    }

    @DeleteMapping("/drafts/{id}")
    public void reject(@PathVariable UUID id) {
        draftService.reject(id);
    }

    @PostMapping("/drafts/{id}/redo")
    public BatchDto redo(@PathVariable UUID id, @RequestBody(required = false) RedoRequest request,
                         @AuthenticationPrincipal StaffUser staff) {
        return service.redo(id, request == null ? null : request.note(), staff.getId());
    }
}
