package com.nbh.backend.variantjobs;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "Tidy colours & sizes" (VARIANTS_PLAN.md phase 6); see {@link TidyOptionsService}. */
@RestController
@RequestMapping("/api/v1/admin/tidy-options")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class TidyOptionsController {

    public record SaveRequest(List<TidyOptionsService.VariantChoice> variants) {}

    private final TidyOptionsService service;

    public TidyOptionsController(TidyOptionsService service) {
        this.service = service;
    }

    @GetMapping
    public TidyOptionsService.Page list(@RequestParam(defaultValue = "50") int limit) {
        return service.list(limit);
    }

    @PostMapping("/{productId}")
    public Map<String, Integer> save(@PathVariable UUID productId, @RequestBody SaveRequest request) {
        return Map.of("saved", service.save(productId, request.variants()));
    }

    @PostMapping("/{productId}/leave")
    public Map<String, Boolean> leave(@PathVariable UUID productId) {
        service.leave(productId);
        return Map.of("left", true);
    }
}
