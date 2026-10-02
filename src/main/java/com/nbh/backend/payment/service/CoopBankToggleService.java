package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.payment.dto.CoopBankStatusDto;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The developer-only on/off switch for Co-op Bank automatic confirmation — see
 * {@code PaymentSettings.coopBankEnabled}'s javadoc for what this does and doesn't do. Deliberately
 * its own tiny service/controller pair (mirrors {@code MockDataSeederService}/{@code MockDataController}),
 * separate from {@link PaymentSettingsService}'s {@code PERM_SETTINGS_MANAGE}-gated update: this is a
 * {@code hasRole('DEVELOPER')} control, not something a shop-owner SUPER_ADMIN should be able to flip.
 */
@Service
public class CoopBankToggleService {

    private final PaymentSettingsRepository repository;

    public CoopBankToggleService(PaymentSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CoopBankStatusDto status() {
        return new CoopBankStatusDto(current().isCoopBankEnabled());
    }

    @Transactional
    public CoopBankStatusDto enable() {
        PaymentSettings settings = current();
        settings.setCoopBankEnabled(true);
        return new CoopBankStatusDto(repository.save(settings).isCoopBankEnabled());
    }

    @Transactional
    public CoopBankStatusDto disable() {
        PaymentSettings settings = current();
        settings.setCoopBankEnabled(false);
        return new CoopBankStatusDto(repository.save(settings).isCoopBankEnabled());
    }

    private PaymentSettings current() {
        return repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ConfigurationException("Payment settings have not been seeded"));
    }
}
