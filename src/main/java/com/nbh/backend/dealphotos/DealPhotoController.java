package com.nbh.backend.dealphotos;

import com.nbh.backend.dealphotos.DealPhotoService.DealPhotoView;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Deal pictures waiting for staff, and their answers (owner, 2026-10-24): Use it, Reject, or Make another. Same
 * permission as the rest of the catalogue's photos.
 */
@RestController
@RequestMapping("/api/v1/admin/deal-photos")
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class DealPhotoController {

    private final DealPhotoService service;

    public DealPhotoController(DealPhotoService service) {
        this.service = service;
    }

    public record Count(long count) {}

    @GetMapping
    public List<DealPhotoView> waiting() {
        return service.waiting();
    }

    @GetMapping("/count")
    public Count count() {
        return new Count(service.waitingCount());
    }

    @PostMapping("/{id}/approve")
    public DealPhotoView approve(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return service.approve(id, staff.getFullName());
    }

    @PostMapping("/{id}/reject")
    public DealPhotoView reject(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return service.reject(id, staff.getFullName());
    }

    /** A new picture for this product now (tens of seconds); it waits for staff like any other. */
    @PostMapping("/variant/{variantId}/make")
    public DealPhotoView makeAnother(@PathVariable UUID variantId) {
        return service.makeAnother(variantId);
    }
}
