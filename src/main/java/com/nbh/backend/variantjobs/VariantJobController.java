package com.nbh.backend.variantjobs;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.variantjobs.VariantJobValidator.Plan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The variant wizard and the Jobs screens (VARIANTS_PLAN.md 3.4). */
@RestController
@RequestMapping("/api/v1/admin/variant-jobs")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class VariantJobController {

    private final VariantJobService service;
    private final SwatchSuggester swatches;
    private final ColourLibraryService library;
    private final PhotoColourReader reader;
    private final VariantPhotoEditor editor;
    private final PhotoTrialService trials;

    public VariantJobController(VariantJobService service, SwatchSuggester swatches, ColourLibraryService library,
                                PhotoColourReader reader, VariantPhotoEditor editor, PhotoTrialService trials) {
        this.trials = trials;
        this.service = service;
        this.swatches = swatches;
        this.library = library;
        this.reader = reader;
        this.editor = editor;
    }

    /** Colours the shop already uses (matching {@code q}), with their usual dot and family, most used first. */
    @GetMapping("/colour-library")
    public List<ColourLibraryService.LibraryColour> colourLibrary(@RequestParam(required = false) String q,
                                                                  @RequestParam(defaultValue = "40") int limit) {
        return library.colours(q, limit);
    }

    /** Products with colours or sizes (matching {@code q}), each with its lists, to copy into the wizard. */
    @GetMapping("/option-sets")
    public List<ColourLibraryService.OptionSet> optionSets(@RequestParam(required = false) String q,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return library.sets(q, limit);
    }

    /** Everything the request would make, its errors, warnings, exceptions summary and estimate. Writes nothing. */
    @PostMapping("/preview")
    public Plan preview(@RequestBody VariantJobRequest request) {
        return service.preview(request);
    }

    /** Checks again and queues the job; 422 with the plan while it has errors. */
    @PostMapping
    public ResponseEntity<?> start(@RequestBody VariantJobRequest request, @AuthenticationPrincipal StaffUser staff) {
        Plan plan = service.preview(request);
        if (!plan.ok()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(plan);
        }
        return ResponseEntity.ok(service.start(request, staff, plan));
    }

    @GetMapping
    public List<VariantJobService.JobDto> list(@RequestParam(defaultValue = "recent") String status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    public VariantJobService.JobDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/publish")
    public VariantJobService.JobDetailDto publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    /** New photos for existing variants, following new instructions; the wrong AI-made photos are replaced. */
    @PostMapping("/redo-photos")
    public VariantJobService.JobDto redoPhotos(@RequestBody VariantJobService.RedoRequest request,
                                               @AuthenticationPrincipal StaffUser staff) {
        return service.redo(request, staff);
    }

    /** "Try 2 first": one or two colours' photos made now, to look at before a whole job is run. */
    @PostMapping("/photo-trial")
    public PhotoTrialService.TrialResult photoTrial(@RequestBody PhotoTrialService.TrialRequest request) {
        return trials.trial(request);
    }

    public record BasePhoto(String url) {}

    /** The photo a job starts from; the wizard shows it so staff can mark the part that changes. */
    @GetMapping("/base-photo")
    public BasePhoto basePhoto(@RequestParam java.util.UUID productId,
                               @RequestParam(required = false) java.util.UUID baseVariantId) {
        return new BasePhoto(trials.basePhotoUrl(productId, baseVariantId));
    }

    public record ReferenceRequest(String image, String mimeType) {}

    public record ReferenceStored(String url) {}

    /** Stores a real photo of the product for the AI to follow (never shown in the shop). */
    @PostMapping("/reference-photo")
    public ReferenceStored referencePhoto(@RequestBody ReferenceRequest request) {
        return new ReferenceStored(trials.uploadReference(request.image(), request.mimeType()));
    }

    @PostMapping("/{id}/retry")
    public VariantJobService.JobDetailDto retry(@PathVariable UUID id) {
        return service.retry(id);
    }

    /** Sets the failed photos/descriptions aside (no retry) and ends a job that stopped; see the service. */
    @PostMapping("/{id}/dismiss")
    public VariantJobService.JobDetailDto dismiss(@PathVariable UUID id) {
        return service.dismiss(id);
    }

    @PostMapping("/{id}/cancel")
    public VariantJobService.JobDetailDto cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    public record SwatchRequest(List<String> names) {}

    /** A dot and family for each colour name staff typed: from the built-in list, else asked of the AI. */
    @PostMapping("/suggest-swatches")
    public List<SwatchSuggester.Suggestion> suggestSwatches(@RequestBody SwatchRequest request) {
        return swatches.suggest(request.names());
    }

    /** {@code image} is the picture as base64 (JPEG, PNG or WebP). */
    public record ReadPhotoRequest(String image, String mimeType, String productName) {}

    /**
     * The colours (and printed codes and sizes) in a picture of variants placed together. Writes nothing: staff look
     * through the answer and tick what to add to the wizard.
     */
    @PostMapping("/read-photo")
    public PhotoColourReader.Reading readPhoto(@RequestBody ReadPhotoRequest request) {
        return reader.read(request.image(), request.mimeType(), request.productName());
    }

    public record EditPhotoRequest(UUID productId, UUID variantId, UUID imageId, String note) {}

    /**
     * Changes a variant's photo as described: the AI edits the variant's current photo (or {@code imageId}), and the
     * result becomes its main photo with the old one kept behind it.
     */
    @PostMapping("/photo-edit")
    public VariantPhotoEditor.Edited editPhoto(@RequestBody EditPhotoRequest request) {
        if (request.productId() == null || request.variantId() == null) {
            throw new com.nbh.backend.common.exception.UnprocessableException("Say which variant's photo to change.");
        }
        return editor.edit(request.productId(), request.variantId(), request.imageId(), request.note());
    }
}
