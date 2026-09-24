package com.nbh.backend.payment.controller;

import com.nbh.backend.payment.dto.PaymentSettingsDto;
import com.nbh.backend.payment.service.PaymentSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The Settings tab's "real" half — bank/paybill/WhatsApp details a customer actually sees. */
@RestController
@RequestMapping("/api/v1/admin/settings/payment")
@PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
public class AdminSettingsController {

    private final PaymentSettingsService service;

    public AdminSettingsController(PaymentSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public PaymentSettingsDto get() {
        return service.get();
    }

    @PutMapping
    public PaymentSettingsDto update(@Valid @RequestBody PaymentSettingsDto request) {
        return service.update(request);
    }
}
