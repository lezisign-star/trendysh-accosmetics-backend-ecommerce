package com.nbh.backend.payment.dto;

import com.nbh.backend.payment.entity.PaymentSettings;
import jakarta.validation.constraints.Size;

/**
 * The admin-editable half of {@code BankPaymentSettings} — bank/paybill/WhatsApp details, exactly
 * what a customer sees on the pay page. A blank field is a deliberate "hide this tab", not an error,
 * so nothing here is required.
 */
public record PaymentSettingsDto(
        @Size(max = 200) String bankName,
        @Size(max = 30) String paybillNumber,
        @Size(max = 200) String accountName,
        @Size(max = 50) String accountNumber,
        @Size(max = 100) String accountBranch,
        @Size(max = 30) String supportWhatsapp,
        /** The business name the customer's phone shows for the paybill, before the PIN. */
        @Size(max = 200) String paybillMerchantName,
        /** What the customer types as the paybill account: placeholders {@code <name> <phone> <order> <ref>}. Blank = the environment default. */
        @Size(max = 100) String paybillAccountTemplate
) {
    public static PaymentSettingsDto from(PaymentSettings s) {
        return new PaymentSettingsDto(s.getBankName(), s.getPaybillNumber(), s.getAccountName(),
                s.getAccountNumber(), s.getAccountBranch(), s.getSupportWhatsapp(),
                s.getPaybillMerchantName(), s.getPaybillAccountTemplate());
    }
}
