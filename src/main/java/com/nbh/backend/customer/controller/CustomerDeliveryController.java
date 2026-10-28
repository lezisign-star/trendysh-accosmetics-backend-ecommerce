package com.nbh.backend.customer.controller;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery a signed-in shopper last used (owner, 2026-10-28: the next order is filled in for them, on any phone they
 * sign in on). It is saved by the checkout itself; this only reads it back, for the shopper's own account.
 */
@RestController
@RequestMapping("/api/v1/customer/delivery")
public class CustomerDeliveryController {

    private final CustomerAccountRepository accounts;

    public CustomerDeliveryController(CustomerAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** {@code saved} is what the checkout sent last time, or null when this shopper has not ordered a delivery yet. */
    public record SavedDelivery(String saved) {}

    @GetMapping
    @Transactional(readOnly = true)
    public SavedDelivery mine(@AuthenticationPrincipal CustomerAccount customer) {
        return new SavedDelivery(accounts.findById(customer.getId()).map(CustomerAccount::getSavedDelivery).orElse(null));
    }
}
