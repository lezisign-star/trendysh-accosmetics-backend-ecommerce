package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.entity.ProductOptionValue;
import com.nbh.backend.catalog.service.ProductOptionsService;
import com.nbh.backend.catalog.service.VariantPriceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * A product's colours and sizes as one list staff can manage (V63; owner, 2026-10-12: "are those colors manageable by
 * the staff and admins"): rename a colour or size everywhere at once, change a colour's dot or family for all its sizes,
 * give a size its label text or photo, put either list in order, and change several variants' prices at once.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}")
public class ProductOptionsController {

    public record ReorderRequest(ProductOptionValue.Kind kind, List<UUID> ids) {}

    private final ProductOptionsService service;
    private final VariantPriceService prices;

    public ProductOptionsController(ProductOptionsService service, VariantPriceService prices) {
        this.service = service;
        this.prices = prices;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/options")
    public List<ProductOptionsService.OptionDto> list(@PathVariable UUID productId) {
        return service.list(productId);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/options/{optionId}")
    public ProductOptionsService.OptionDto update(@PathVariable UUID productId, @PathVariable UUID optionId,
                                                  @RequestBody ProductOptionsService.OptionChange change) {
        return service.update(productId, optionId, change);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/options/order")
    public List<ProductOptionsService.OptionDto> reorder(@PathVariable UUID productId,
                                                         @RequestBody ReorderRequest request) {
        return service.reorder(productId, request.kind(), request.ids());
    }

    /** Several variants' prices at once; see {@link VariantPriceService}. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/variants/prices")
    public VariantPriceService.Result prices(@PathVariable UUID productId, @RequestBody VariantPriceService.Change change) {
        return prices.apply(productId, change);
    }
}
