package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.QuickEditDtos.Row;
import com.nbh.backend.catalog.dto.QuickEditDtos.SaveResult;
import com.nbh.backend.catalog.dto.QuickEditDtos.SaveRow;
import com.nbh.backend.catalog.service.QuickEditService;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The quick-edit table: every option's numbers in one list (see {@link QuickEditService}). Open to whoever may change
 * prices or stock; which numbers on a row they may change is checked per row, against the same permission each
 * number's own screen asks for.
 */
@RestController
@RequestMapping("/api/v1/admin/quick-edit")
public class QuickEditController {

    private static final String PRICE_AUTHORITY = "PERM_PRODUCT_MANAGE";
    private static final String STOCK_AUTHORITY = "PERM_STOCK_ADJUST";

    private final QuickEditService quickEditService;

    public QuickEditController(QuickEditService quickEditService) {
        this.quickEditService = quickEditService;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE') or hasAuthority('PERM_STOCK_ADJUST')")
    @GetMapping("/rows")
    public Page<Row> rows(@RequestParam(required = false) String q,
                          @RequestParam(required = false) UUID categoryId,
                          @PageableDefault(size = 50) Pageable pageable) {
        return quickEditService.rows(q, categoryId, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE') or hasAuthority('PERM_STOCK_ADJUST')")
    @PutMapping("/variants/{variantId}")
    public SaveResult save(@PathVariable UUID variantId, @RequestBody SaveRow request,
                           @AuthenticationPrincipal StaffUser staff) {
        return quickEditService.save(variantId, request, holds(staff, PRICE_AUTHORITY), holds(staff, STOCK_AUTHORITY), staff);
    }

    private static boolean holds(StaffUser staff, String authority) {
        return staff.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
