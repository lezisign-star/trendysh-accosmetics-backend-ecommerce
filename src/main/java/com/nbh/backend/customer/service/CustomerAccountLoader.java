package com.nbh.backend.customer.service;

import com.nbh.backend.customer.entity.CustomerAccount;
import com.nbh.backend.customer.repository.CustomerAccountRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a customer for JWT authentication. Intentionally NOT a
 * {@code UserDetailsService} bean: staff login relies on Spring's single
 * authentication provider being backed by the staff service, and a customer
 * email must never be resolvable through that path.
 */
@Service
public class CustomerAccountLoader {

    private final CustomerAccountRepository accounts;

    public CustomerAccountLoader(CustomerAccountRepository accounts) {
        this.accounts = accounts;
    }

    public CustomerAccount loadByEmail(String email) {
        return accounts.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No customer account for " + email));
    }
}
