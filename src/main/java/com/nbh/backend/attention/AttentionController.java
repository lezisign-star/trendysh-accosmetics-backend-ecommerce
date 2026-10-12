package com.nbh.backend.attention;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard's "Needs attention" panel and the header's count (VARIANTS_PLAN.md 3.5). */
@RestController
@RequestMapping("/api/v1/admin/attention")
public class AttentionController {

    private final AttentionService service;

    public AttentionController(AttentionService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE') or hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_STOCK_RECEIVE')")
    @GetMapping
    public AttentionService.Attention list() {
        return service.list();
    }
}
