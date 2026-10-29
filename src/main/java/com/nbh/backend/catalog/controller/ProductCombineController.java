package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.service.ProductCombineService;
import com.nbh.backend.catalog.service.ProductCombineService.CombineDto;
import com.nbh.backend.catalog.service.ProductCombineService.CombineRequest;
import com.nbh.backend.catalog.service.ProductCombineService.Preview;
import com.nbh.backend.catalog.service.ProductCombineService.PreviewRequest;
import com.nbh.backend.catalog.service.ProductCombineService.Result;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Combining products into one and splitting them back (owner, 2026-10-29); see {@link ProductCombineService}. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductCombineController {

    private final ProductCombineService combine;

    public ProductCombineController(ProductCombineService combine) {
        this.combine = combine;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/combine/preview")
    public Preview preview(@RequestBody PreviewRequest request) {
        return combine.preview(request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/combine")
    public Result combine(@RequestBody CombineRequest request, @AuthenticationPrincipal StaffUser staff) {
        return combine.combine(request, staff == null ? null : staff.getFullName());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/{id}/combines")
    public List<CombineDto> combines(@PathVariable UUID id) {
        return combine.list(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/combines/{combineId}/split")
    public void split(@PathVariable UUID combineId, @AuthenticationPrincipal StaffUser staff) {
        combine.split(combineId, staff == null ? null : staff.getFullName());
    }
}
