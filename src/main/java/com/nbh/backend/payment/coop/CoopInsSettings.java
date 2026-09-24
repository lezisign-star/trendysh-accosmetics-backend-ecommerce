package com.nbh.backend.payment.coop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for receiving Co-op Bank INS notifications. All blank by default, which means "reject everything":
 * the endpoint does nothing until real credentials are agreed with the bank.
 *
 * <p>{@code autoMatch} defaults to false on purpose. The feed has never been tested against the real bank, and INS
 * authenticates with a shared Basic credential only (no signature), so until it has been proven end to end each
 * credit is recorded for staff to allocate with one click instead of confirming an order by itself.
 */
@Component
public class CoopInsSettings {

    private final String username;
    private final String password;
    private final String accountNumber;
    private final boolean autoMatch;

    public CoopInsSettings(@Value("${app.coop.ins.username:}") String username,
                           @Value("${app.coop.ins.password:}") String password,
                           @Value("${app.coop.ins.account-number:}") String accountNumber,
                           @Value("${app.coop.ins.auto-match:false}") boolean autoMatch) {
        this.username = username;
        this.password = password;
        this.accountNumber = accountNumber;
        this.autoMatch = autoMatch;
    }

    public boolean configured() { return !username.isBlank() && !password.isBlank(); }

    public String username() { return username; }

    public String password() { return password; }

    /** When set, notifications for any other account number are ignored. */
    public String accountNumber() { return accountNumber; }

    public boolean autoMatch() { return autoMatch; }
}
