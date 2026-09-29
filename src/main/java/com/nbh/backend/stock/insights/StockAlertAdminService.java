package com.nbh.backend.stock.insights;

import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import com.nbh.backend.stock.insights.StockInsightsDtos.AlertSettingsDto;
import com.nbh.backend.stock.insights.StockInsightsDtos.Recipient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** What the admin Settings "Stock alerts" section reads and changes: the numbers, and who gets the emails. */
@Service
public class StockAlertAdminService {

    private final StockAlertSettingsService settingsService;
    private final StaffUserRepository staffUsers;

    public StockAlertAdminService(StockAlertSettingsService settingsService, StaffUserRepository staffUsers) {
        this.settingsService = settingsService;
        this.staffUsers = staffUsers;
    }

    @Transactional(readOnly = true)
    public AlertSettingsDto get() {
        StockAlertSettings s = settingsService.get();
        return new AlertSettingsDto(s.getLeadTimeDays(), s.getSafetyDays(), s.isDigestEnabled(), recipients());
    }

    @Transactional
    public AlertSettingsDto update(int leadTimeDays, int safetyDays, boolean digestEnabled) {
        settingsService.update(leadTimeDays, safetyDays, digestEnabled);
        return get();
    }

    @Transactional
    public AlertSettingsDto setRecipient(UUID staffId, boolean receives) {
        StaffUser user = staffUsers.findById(staffId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
        user.setReceivesStockAlerts(receives);
        staffUsers.save(user);
        return get();
    }

    private List<Recipient> recipients() {
        return staffUsers.findByDeletedFalseOrderByCreatedAtDesc().stream()
                .filter(StaffUser::isEnabled)
                .map(u -> new Recipient(u.getId(), u.getFullName(), u.getEmail(),
                        u.getStaffRole().getDisplayName(), u.isReceivesStockAlerts()))
                .toList();
    }
}
