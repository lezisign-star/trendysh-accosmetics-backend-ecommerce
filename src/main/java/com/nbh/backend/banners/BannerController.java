package com.nbh.backend.banners;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Occasion banners (owner, 2026-10-29): what the shop shows, and staff's picking and occasions. See {@link BannerService}. */
@RestController
public class BannerController {

    private final BannerService banners;

    public BannerController(BannerService banners) {
        this.banners = banners;
    }

    /** Public: the occasion on now, its picked banners, and a sale coming up. */
    @GetMapping("/api/v1/catalog/occasion")
    public BannerService.ShopView shop() {
        return banners.shopView();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/api/v1/admin/banners")
    public List<BannerService.AdminBanner> list() {
        return banners.forStaff();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/banners/{id}/pick")
    public void pick(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        banners.pick(id, staff == null ? null : staff.getFullName());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/banners/{id}/reject")
    public void reject(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        banners.reject(id, staff == null ? null : staff.getFullName());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/api/v1/admin/banners/{id}")
    public void editLine(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        banners.editLine(id, body.get("line"));
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/banners/make")
    public void make(@RequestBody BannerService.MakeRequest request) {
        banners.makeNow(request);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/api/v1/admin/occasions")
    public List<BannerService.AdminOccasion> occasions() {
        return banners.occasionsForStaff();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/api/v1/admin/occasions/{id}")
    public void changeOccasion(@PathVariable UUID id, @RequestBody BannerService.OccasionChange change) {
        banners.changeOccasion(id, change);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/api/v1/admin/occasions")
    public void addOccasion(@RequestBody BannerService.NewOccasion occasion) {
        banners.addOccasion(occasion);
    }
}
