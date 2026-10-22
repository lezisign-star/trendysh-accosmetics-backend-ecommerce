package com.nbh.backend.shop;

import com.nbh.backend.shop.ShopModeDtos.ShopModeAdminDto;
import com.nbh.backend.shop.ShopModeDtos.ShopModeDto;
import com.nbh.backend.shop.ShopModeDtos.SetShopModeRequest;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the shop takes orders. The read is public (the storefront asks on every visit: {@code /api/v1/catalog/**} is open);
 * changing it is for the owner and the developer only, the same two accounts that hold every permission, so the switch
 * cannot be flipped by a manager.
 */
@RestController
public class ShopModeController {

    private final ShopSettingsService settings;

    public ShopModeController(ShopSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/v1/catalog/shop-mode")
    public ShopModeDto publicMode() {
        return new ShopModeDto(settings.get().getOrderingMode());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DEVELOPER')")
    @GetMapping("/api/v1/admin/shop-mode")
    public ShopModeAdminDto adminMode() {
        return ShopModeAdminDto.from(settings.get());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DEVELOPER')")
    @PutMapping("/api/v1/admin/shop-mode")
    public ShopModeAdminDto change(@Valid @RequestBody SetShopModeRequest request, @AuthenticationPrincipal StaffUser staff) {
        return ShopModeAdminDto.from(settings.setOrderingMode(request.orderingMode(), staff.getFullName()));
    }
}
