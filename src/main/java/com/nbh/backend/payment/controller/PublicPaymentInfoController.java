package com.nbh.backend.payment.controller;

import com.nbh.backend.payment.service.BankPaymentSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the checkout's payment step shows BEFORE an order exists: the Paybill number, the merchant
 * name the customer's phone will display, and the account number they will type. Public — it is
 * exactly what the pay page shows after checkout. The account number is resolved by the server, from
 * the name and phone entered so far, so the preview can never disagree with the real order's.
 */
@RestController
@RequestMapping("/api/v1/payments/paybill-info")
public class PublicPaymentInfoController {

    private final BankPaymentSettings settings;

    public PublicPaymentInfoController(BankPaymentSettings settings) {
        this.settings = settings;
    }

    /**
     * {@code configured} is false until an admin has entered a Paybill number — the checkout then just says
     * payment details follow the order. {@code accountNumberFinal} is false when the admin's template uses
     * the order number or payment reference, which only exist once the order is placed.
     */
    public record PaybillPreview(boolean configured, String paybillNumber, String merchantName,
                                 String accountNumber, boolean accountNumberFinal) {}

    @GetMapping
    public PaybillPreview preview(@RequestParam(required = false) String name,
                                  @RequestParam(required = false) String phone) {
        if (settings.paybillNumber().isBlank()) {
            return new PaybillPreview(false, "", "", "", true);
        }
        BankPaymentSettings.AccountPreview account = settings.previewAccount(name, phone);
        return new PaybillPreview(true, settings.paybillNumber(), settings.paybillMerchantName(),
                account.accountNumber(), account.finalValue());
    }
}
