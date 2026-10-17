package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.service.RangeDescriptionService;
import com.nbh.backend.catalog.service.RangeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** A range's configuration: what its variants share, and which details may differ per variant. See {@link RangeService}. */
@RestController
@RequestMapping("/api/v1/products/{productId}/range")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class RangeController {

    private final RangeService service;
    private final RangeDescriptionService descriptions;

    public RangeController(RangeService service, RangeDescriptionService descriptions) {
        this.service = service;
        this.descriptions = descriptions;
    }

    /** The configuration, or (until it is confirmed) the one suggested from the variants. */
    @GetMapping
    public RangeService.RangeDto get(@PathVariable UUID productId) {
        return service.get(productId);
    }

    /** What saving would do, and the variants with their own value to ask about. Writes nothing. */
    @PostMapping("/preview")
    public RangeService.Preview preview(@PathVariable UUID productId, @RequestBody RangeService.RangeInput in) {
        return service.preview(productId, in);
    }

    /** Saves (and, the first time, confirms) the configuration and writes the shared values into the variants. */
    @PutMapping
    public RangeService.RangeDto save(@PathVariable UUID productId, @RequestBody RangeService.RangeInput in) {
        return service.save(productId, in);
    }

    /** AI drafts of the variants' own descriptions, from the shared one, for staff to read. Writes nothing. */
    @PostMapping("/descriptions/write")
    public java.util.List<RangeDescriptionService.Draft> writeDescriptions(@PathVariable UUID productId,
                                                                          @RequestBody(required = false) RangeDescriptionService.WriteRequest r) {
        return descriptions.write(productId, r);
    }

    public record Saved(int saved) {}

    /** Stores the descriptions staff kept (the range then lets descriptions differ). */
    @PutMapping("/descriptions")
    public Saved saveDescriptions(@PathVariable UUID productId, @RequestBody RangeDescriptionService.SaveRequest r) {
        return new Saved(descriptions.save(productId, r));
    }
}
