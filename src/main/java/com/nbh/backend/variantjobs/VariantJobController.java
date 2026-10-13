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

    public VariantJobController(VariantJobService service, SwatchSuggester swatches, ColourLibraryService library) {
        this.service = service;
        this.swatches = swatches;
        this.library = library;
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
}
