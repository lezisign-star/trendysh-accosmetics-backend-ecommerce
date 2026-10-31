package com.nbh.backend.banners;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The banner builder (owner, 2026-10-31): staff's banners and pictures, and the live ones for the shop. See {@link PromoBannerService}. */
@RestController
public class PromoBannerController {

    private final PromoBannerService banners;

    public PromoBannerController(PromoBannerService banners) {
        this.banners = banners;
    }

    /** Public: the live hero, strip and tile banners, in order. */
    @GetMapping("/api/v1/catalog/promo-banners")
    public List<PromoBannerService.PublicView> live() {
        return banners.live();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/api/v1/admin/promo-banners")
    public List<PromoBannerService.AdminView> list() {
        return banners.list();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners")
    public PromoBannerService.AdminView create(@RequestBody PromoBannerService.NewBanner in,
                                               @AuthenticationPrincipal StaffUser staff) {
        return banners.create(in, name(staff));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/api/v1/admin/promo-banners/{id}")
    public PromoBannerService.AdminView get(@PathVariable UUID id) {
        return banners.get(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/api/v1/admin/promo-banners/{id}")
    public PromoBannerService.AdminView update(@PathVariable UUID id, @RequestBody PromoBannerService.Edit in) {
        return banners.update(id, in);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/api/v1/admin/promo-banners/{id}")
    public void delete(@PathVariable UUID id) {
        banners.delete(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/approve")
    public PromoBannerService.AdminView approve(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return banners.approve(id, name(staff));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/pause")
    public PromoBannerService.AdminView pause(@PathVariable UUID id) {
        return banners.pause(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/resume")
    public PromoBannerService.AdminView resume(@PathVariable UUID id) {
        return banners.resume(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/end")
    public PromoBannerService.AdminView end(@PathVariable UUID id) {
        return banners.endNow(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/duplicate")
    public PromoBannerService.AdminView duplicate(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return banners.duplicate(id, name(staff));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/pictures/generate")
    public PromoBannerService.AdminView generate(@PathVariable UUID id, @RequestBody PromoBannerService.Generate in) {
        return banners.generate(id, in);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/pictures/upload")
    public PromoBannerService.AdminView upload(@PathVariable UUID id, @RequestBody PromoBannerService.Upload in) {
        return banners.upload(id, in);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/pictures/from-products")
    public PromoBannerService.AdminView fromProducts(@PathVariable UUID id) {
        return banners.fromProducts(id);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/promo-banners/{id}/pictures/{pictureId}/choose")
    public PromoBannerService.AdminView choose(@PathVariable UUID id, @PathVariable UUID pictureId) {
        return banners.choosePicture(id, pictureId);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/api/v1/admin/promo-banners/{id}/pictures/{pictureId}")
    public PromoBannerService.AdminView deletePicture(@PathVariable UUID id, @PathVariable UUID pictureId) {
        return banners.deletePicture(id, pictureId);
    }

    private static String name(StaffUser staff) {
        return staff == null ? null : staff.getFullName();
    }
}
