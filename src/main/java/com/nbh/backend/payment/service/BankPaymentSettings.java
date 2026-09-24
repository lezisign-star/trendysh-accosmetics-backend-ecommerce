package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Where the customer sends money, and how long they have to do it. {@code mode}/{@code paymentWindow}/
 * the paybill account template stay operational config (env-var only — not something a shop owner
 * should casually flip from a settings screen); the actual bank/paybill/WhatsApp details are read
 * fresh from {@code payment_settings} on every call (see {@code PaymentSettingsSeeder} for how that
 * table gets its first row) so an admin edit takes effect immediately, no redeploy needed. Unset
 * details are simply omitted from the payment page: a blank paybill number hides the Paybill tab,
 * blank account details hide the bank-transfer tab.
 */
@Service
public class BankPaymentSettings {

    public enum CollectionMode { BANK, MPESA }

    private final PaymentSettingsRepository settingsRepository;
    private final CollectionMode mode;
    private final Duration paymentWindow;
    private final String paybillAccountTemplate;

    public BankPaymentSettings(
            PaymentSettingsRepository settingsRepository,
            @Value("${app.payments.collection-mode:BANK}") CollectionMode mode,
            @Value("${app.payments.window-minutes:45}") long windowMinutes,
            @Value("${app.bank.paybill-account-template:<ref>}") String paybillAccountTemplate) {
        this.settingsRepository = settingsRepository;
        this.mode = mode;
        this.paymentWindow = Duration.ofMinutes(windowMinutes);
        this.paybillAccountTemplate = paybillAccountTemplate;
    }

    public CollectionMode mode() { return mode; }

    public boolean bankMode() { return mode == CollectionMode.BANK; }

    public Duration paymentWindow() { return paymentWindow; }

    public String bankName() { return orEmpty(current().getBankName()); }

    public String paybillNumber() { return orEmpty(current().getPaybillNumber()); }

    /** The account string the customer types after the paybill number, e.g. template "01100XXXX#<ref>". */
    public String paybillAccountFor(String reference) {
        return paybillAccountTemplate.replace("<ref>", reference);
    }

    public String accountName() { return orEmpty(current().getAccountName()); }

    public String accountNumber() { return orEmpty(current().getAccountNumber()); }

    public String accountBranch() { return orEmpty(current().getAccountBranch()); }

    public String supportWhatsapp() { return orEmpty(current().getSupportWhatsapp()); }

    /** Spring Data's own generated query methods already run in their own read-only transaction. */
    private PaymentSettings current() {
        // §4 C7: a missing seeded row is a deployment problem, not a customer/admin input problem.
        return settingsRepository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ConfigurationException("Payment settings have not been seeded"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
