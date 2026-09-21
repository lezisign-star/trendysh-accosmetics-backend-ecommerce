package com.nbh.backend.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Where the customer sends money, and how long they have to do it. Every value
 * is configuration so the client can switch bank (Co-op, Family, ...) without a
 * code change. Unset details are simply omitted from the payment page: a
 * blank {@code paybill-number} hides the Paybill tab, blank account details
 * hide the bank-transfer tab.
 */
@Component
public class BankPaymentSettings {

    public enum CollectionMode { BANK, MPESA }

    private final CollectionMode mode;
    private final Duration paymentWindow;
    private final String bankName;
    private final String paybillNumber;
    private final String paybillAccountTemplate;
    private final String accountName;
    private final String accountNumber;
    private final String accountBranch;
    private final String supportWhatsapp;

    public BankPaymentSettings(
            @Value("${app.payments.collection-mode:BANK}") CollectionMode mode,
            @Value("${app.payments.window-minutes:45}") long windowMinutes,
            @Value("${app.bank.name:}") String bankName,
            @Value("${app.bank.paybill-number:}") String paybillNumber,
            @Value("${app.bank.paybill-account-template:<ref>}") String paybillAccountTemplate,
            @Value("${app.bank.account-name:}") String accountName,
            @Value("${app.bank.account-number:}") String accountNumber,
            @Value("${app.bank.account-branch:}") String accountBranch,
            @Value("${app.bank.support-whatsapp:}") String supportWhatsapp) {
        this.mode = mode;
        this.paymentWindow = Duration.ofMinutes(windowMinutes);
        this.bankName = bankName;
        this.paybillNumber = paybillNumber;
        this.paybillAccountTemplate = paybillAccountTemplate;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.accountBranch = accountBranch;
        this.supportWhatsapp = supportWhatsapp;
    }

    public CollectionMode mode() { return mode; }

    public boolean bankMode() { return mode == CollectionMode.BANK; }

    public Duration paymentWindow() { return paymentWindow; }

    public String bankName() { return bankName; }

    public String paybillNumber() { return paybillNumber; }

    /** The account string the customer types after the paybill number, e.g. template "01100XXXX#<ref>". */
    public String paybillAccountFor(String reference) {
        return paybillAccountTemplate.replace("<ref>", reference);
    }

    public String accountName() { return accountName; }

    public String accountNumber() { return accountNumber; }

    public String accountBranch() { return accountBranch; }

    public String supportWhatsapp() { return supportWhatsapp; }
}
