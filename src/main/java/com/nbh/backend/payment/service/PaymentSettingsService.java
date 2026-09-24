package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.payment.dto.PaymentSettingsDto;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentSettingsService {

    private final PaymentSettingsRepository repository;

    public PaymentSettingsService(PaymentSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PaymentSettingsDto get() {
        return PaymentSettingsDto.from(current());
    }

    @Transactional
    public PaymentSettingsDto update(PaymentSettingsDto request) {
        PaymentSettings settings = current();
        settings.setBankName(blankToNull(request.bankName()));
        settings.setPaybillNumber(blankToNull(request.paybillNumber()));
        settings.setAccountName(blankToNull(request.accountName()));
        settings.setAccountNumber(blankToNull(request.accountNumber()));
        settings.setAccountBranch(blankToNull(request.accountBranch()));
        settings.setSupportWhatsapp(blankToNull(request.supportWhatsapp()));
        return PaymentSettingsDto.from(repository.save(settings));
    }

    private PaymentSettings current() {
        // §4 C7: a missing seeded row is a deployment problem (PaymentSettingsSeeder didn't run
        // yet, or ran against the wrong database), not something the caller's request got wrong.
        return repository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ConfigurationException("Payment settings have not been seeded"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
