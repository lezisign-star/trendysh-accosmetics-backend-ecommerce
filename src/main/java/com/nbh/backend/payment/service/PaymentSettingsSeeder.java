package com.nbh.backend.payment.service;

import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Carries the live {@code app.bank.*} env-var values into the new {@code payment_settings} table
 * exactly once, on whichever boot first finds the table empty — so cutting a real deployment over
 * from static config to admin-editable settings doesn't change what a customer sees at checkout.
 * Every boot after that is a no-op: once the row exists (whether from this seeder or a later admin
 * edit), it's never touched here again. Mirrors {@code StaffUserSeeder}'s same one-time-only shape.
 */
@Slf4j
@Component
public class PaymentSettingsSeeder {

    private final PaymentSettingsRepository repository;

    @Value("${app.bank.name:}") private String bankName;
    @Value("${app.bank.paybill-number:}") private String paybillNumber;
    @Value("${app.bank.account-name:}") private String accountName;
    @Value("${app.bank.account-number:}") private String accountNumber;
    @Value("${app.bank.account-branch:}") private String accountBranch;
    @Value("${app.bank.support-whatsapp:}") private String supportWhatsapp;

    public PaymentSettingsSeeder(PaymentSettingsRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seed() {
        if (repository.count() > 0) {
            return;
        }
        PaymentSettings settings = new PaymentSettings();
        settings.setBankName(blankToNull(bankName));
        settings.setPaybillNumber(blankToNull(paybillNumber));
        settings.setAccountName(blankToNull(accountName));
        settings.setAccountNumber(blankToNull(accountNumber));
        settings.setAccountBranch(blankToNull(accountBranch));
        settings.setSupportWhatsapp(blankToNull(supportWhatsapp));
        repository.save(settings);
        log.info("Seeded payment settings from the current app.bank.* config (one-time — "
                + "admin edits in Settings take over from here, env vars are no longer read)");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
