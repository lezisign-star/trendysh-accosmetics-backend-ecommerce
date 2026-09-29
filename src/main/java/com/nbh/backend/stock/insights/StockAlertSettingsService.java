package com.nbh.backend.stock.insights;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class StockAlertSettingsService {

    private final StockAlertSettingsRepository repository;

    public StockAlertSettingsService(StockAlertSettingsRepository repository) {
        this.repository = repository;
    }

    /** The one settings row; created on the fly if a database somehow has none. */
    @Transactional
    public StockAlertSettings get() {
        return repository.findFirstByOrderByCreatedAtAsc().orElseGet(() -> repository.save(new StockAlertSettings()));
    }

    @Transactional
    public StockAlertSettings update(int leadTimeDays, int safetyDays, boolean digestEnabled) {
        if (leadTimeDays < 0 || leadTimeDays > 120) {
            throw new IllegalArgumentException("Lead time must be between 0 and 120 days");
        }
        if (safetyDays < 0 || safetyDays > 60) {
            throw new IllegalArgumentException("Safety days must be between 0 and 60");
        }
        StockAlertSettings settings = get();
        settings.setLeadTimeDays(leadTimeDays);
        settings.setSafetyDays(safetyDays);
        settings.setDigestEnabled(digestEnabled);
        return repository.save(settings);
    }

    @Transactional
    public void markDigestSent(LocalDate day) {
        StockAlertSettings settings = get();
        settings.setLastDigestOn(day);
        repository.save(settings);
    }
}
