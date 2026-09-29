package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.BulkProductActionResult;
import com.nbh.backend.catalog.dto.FlashDealRequest;
import com.nbh.backend.catalog.service.FlashDealBulkService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Start or end flash deals on whole products — "20% off until Friday" — from the admin product screens. */
@RestController
@RequestMapping("/api/v1/products/flash-deal")
public class FlashDealController {

    private final FlashDealBulkService service;

    public FlashDealController(FlashDealBulkService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    public BulkProductActionResult start(@Valid @RequestBody FlashDealRequest body) {
        return service.start(body.productIds(), body.percentOff(), body.endsAt());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/end")
    public BulkProductActionResult end(@Valid @RequestBody FlashDealRequest.End body) {
        return service.end(body.productIds());
    }
}
