package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.PromoCampaignDto;
import com.nbh.backend.catalog.service.PromoCampaignService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff CRUD for the site-wide promo campaign — see {@link PromoCampaignService}'s javadoc for the
 * "at most one active at a time" rule. Same permission as everything else merchandising-related
 * (categories, products): this isn't its own concern, it's a form of product management.
 */
@RestController
@RequestMapping("/api/v1/admin/promo-campaigns")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class PromoCampaignController {

    private final PromoCampaignService service;
    private final Clock clock;

    public PromoCampaignController(PromoCampaignService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    @Transactional
    public List<PromoCampaignDto> list() {
        Instant now = Instant.now(clock);
        return service.list().stream().map(c -> PromoCampaignDto.from(c, now)).toList();
    }

    @PostMapping
    @Transactional
    public PromoCampaignDto create(@Valid @RequestBody CampaignRequest request) {
        var c = service.create(request.name(), request.percentOff(), request.startsAt(), request.endsAt(),
                request.isActive() == null || request.isActive());
        return PromoCampaignDto.from(c, Instant.now(clock));
    }

    @PatchMapping("/{id}")
    @Transactional
    public PromoCampaignDto update(@PathVariable UUID id, @Valid @RequestBody CampaignRequest request) {
        var c = service.update(id, request.name(), request.percentOff(), request.startsAt(), request.endsAt(),
                request.isActive() == null || request.isActive());
        return PromoCampaignDto.from(c, Instant.now(clock));
    }

    /** The kill-switch — flips is_active without touching the dates, so it can be turned back on as-is. */
    @PutMapping("/{id}/active")
    @Transactional
    public PromoCampaignDto setActive(@PathVariable UUID id, @RequestBody ActiveRequest request) {
        var c = service.setActive(id, request.isActive());
        return PromoCampaignDto.from(c, Instant.now(clock));
    }

    public record CampaignRequest(
            @NotBlank String name,
            @NotNull BigDecimal percentOff,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            Boolean isActive) {}

    public record ActiveRequest(boolean isActive) {}
}
