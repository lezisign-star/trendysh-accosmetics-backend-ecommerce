package com.nbh.backend.stock.insights;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.insights.StockInsightsDtos.AlertSettingsDto;
import com.nbh.backend.stock.insights.StockInsightsDtos.AlertSettingsRequest;
import com.nbh.backend.stock.insights.StockInsightsDtos.RecipientRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Settings → Stock alerts: lead time, the morning digest, and who receives the emails. Admins only. */
@RestController
@RequestMapping("/api/v1/admin/stock-alerts")
@PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
public class StockAlertSettingsController {

    private final StockAlertAdminService adminService;
    private final StockAlertService alertService;

    public StockAlertSettingsController(StockAlertAdminService adminService, StockAlertService alertService) {
        this.adminService = adminService;
        this.alertService = alertService;
    }

    @GetMapping
    public AlertSettingsDto get() {
        return adminService.get();
    }

    @PutMapping("/settings")
    public AlertSettingsDto update(@Valid @RequestBody AlertSettingsRequest body) {
        return adminService.update(body.leadTimeDays(), body.safetyDays(), body.digestEnabled());
    }

    @PatchMapping("/recipients/{staffId}")
    public AlertSettingsDto setRecipient(@PathVariable UUID staffId, @RequestBody RecipientRequest body) {
        return adminService.setRecipient(staffId, body.receives());
    }

    /** Emails the current digest to the person asking, so they can check that alert emails really arrive. */
    @PostMapping("/test")
    public Map<String, Object> sendTest(@AuthenticationPrincipal StaffUser staff) {
        int items = alertService.sendTestDigest(staff);
        return Map.of("sentTo", staff.getEmail(), "items", items);
    }
}
