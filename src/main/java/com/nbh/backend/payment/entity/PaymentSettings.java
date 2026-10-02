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

    /** The business name the customer's phone shows before they enter their PIN (V37). */
    @Column(name = "paybill_merchant_name", length = 200)
    private String paybillMerchantName;

    /** What to type as the Account Number: placeholders {@code <name> <phone> <order> <ref>}. Null falls back to the env template (V37). */
    @Column(name = "paybill_account_template", length = 100)
    private String paybillAccountTemplate;

    @Column(name = "account_name", length = 200)
    private String accountName;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "account_branch", length = 100)
    private String accountBranch;

    @Column(name = "support_whatsapp", length = 30)
    private String supportWhatsapp;

    /**
     * Developer-only visibility switch for Co-op Bank automatic confirmation (V46) — defaults off.
     * The INS webhook receiver ({@code POST /payments/coop/ins}) already exists and self-disables
     * safely with blank credentials; this flag doesn't wire anything real, it only lets a developer
     * show the capability exists without implying the real bank connection works. See
     * {@code com.nbh.backend.payment.controller.CoopBankToggleController}.
     */
    @Column(name = "coop_bank_enabled", nullable = false)
    private boolean coopBankEnabled = false;
}
