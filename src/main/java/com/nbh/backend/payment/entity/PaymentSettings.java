package com.nbh.backend.payment.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Singleton row (exactly one, enforced by {@code PaymentSettingsSeeder}/{@code AdminSettingsService}
 * always working against the first row, never inserting a second) — the bank/paybill/WhatsApp
 * details a customer sees on the pay page. Blank/null fields hide their tab, same semantics as the
 * old {@code app.bank.*} env vars this replaces.
 */
@Getter
@Setter
@Entity
@Table(name = "payment_settings")
public class PaymentSettings extends BaseEntity {

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "paybill_number", length = 30)
    private String paybillNumber;

    @Column(name = "account_name", length = 200)
    private String accountName;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "account_branch", length = 100)
    private String accountBranch;

    @Column(name = "support_whatsapp", length = 30)
    private String supportWhatsapp;
}
