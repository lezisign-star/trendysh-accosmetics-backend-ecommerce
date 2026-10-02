package com.nbh.backend.payment.controller;

import com.nbh.backend.payment.dto.CoopBankStatusDto;
import com.nbh.backend.payment.service.CoopBankToggleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer-only visibility switch for Co-op Bank automatic confirmation — see
 * {@code PaymentSettings.coopBankEnabled}'s javadoc. {@code hasRole('DEVELOPER')}, same reasoning
 * as {@code MockDataController}: the client's own SUPER_ADMIN holds the same permission set as
 * DEVELOPER, so only the role check keeps this out of their hands.
 */
@RestController
@RequestMapping("/api/v1/admin/coop-bank")
@PreAuthorize("hasRole('DEVELOPER')")
public class CoopBankToggleController {

    private final CoopBankToggleService service;

    public CoopBankToggleController(CoopBankToggleService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public CoopBankStatusDto status() {
        return service.status();
    }

    @PostMapping("/enable")
    public CoopBankStatusDto enable() {
        return service.enable();
    }

    @PostMapping("/disable")
    public CoopBankStatusDto disable() {
        return service.disable();
    }
}
