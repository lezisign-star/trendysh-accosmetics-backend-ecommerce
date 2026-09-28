package com.nbh.backend.payment.service;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.payment.entity.PaymentSettings;
import com.nbh.backend.payment.repository.PaymentSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the customer sends money, and how long they have to do it. {@code mode}/{@code paymentWindow}
 * stay operational config (env-var only — not something a shop owner should casually flip from a
 * settings screen); the bank/paybill/WhatsApp details, the paybill's merchant name and the account
 * number template are read fresh from {@code payment_settings} on every call (see
 * {@code PaymentSettingsSeeder} for how that table gets its first row) so an admin edit takes effect
 * immediately, no redeploy needed. Unset details are simply omitted from the payment page: a blank
 * paybill number hides the Paybill tab, blank account details hide the bank-transfer tab.
 *
 * <p>The account number a customer types after the paybill number comes from a template with
 * placeholders resolved per order: {@code <name>} (the customer's name, so staff can match a payment
 * by who paid), {@code <phone>}, {@code <order>} (the order number) and {@code <ref>} (the per-order
 * payment reference). The admin-set template wins; the {@code app.bank.paybill-account-template}
 * environment value is only the fallback.
 */
@Service
public class BankPaymentSettings {

    public enum CollectionMode { BANK, MPESA }

    /** The placeholders an account-number template may contain. */
    public static final Set<String> ACCOUNT_PLACEHOLDERS = Set.of("<name>", "<phone>", "<order>", "<ref>");

    /** Many paybills cap the Account Number field around 20 characters. */
    private static final int NAME_MAX = 20;
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^<>]*>");

    private final PaymentSettingsRepository settingsRepository;
    private final CollectionMode mode;
    private final Duration paymentWindow;
    private final String envAccountTemplate;

    public BankPaymentSettings(
            PaymentSettingsRepository settingsRepository,
            @Value("${app.payments.collection-mode:BANK}") CollectionMode mode,
            @Value("${app.payments.window-minutes:45}") long windowMinutes,
            @Value("${app.bank.paybill-account-template:<name>}") String envAccountTemplate) {
        this.settingsRepository = settingsRepository;
        this.mode = mode;
        this.paymentWindow = Duration.ofMinutes(windowMinutes);
        this.envAccountTemplate = envAccountTemplate;
    }

    public CollectionMode mode() { return mode; }

    public boolean bankMode() { return mode == CollectionMode.BANK; }

    public Duration paymentWindow() { return paymentWindow; }

    public String bankName() { return orEmpty(current().getBankName()); }

    public String paybillNumber() { return orEmpty(current().getPaybillNumber()); }

    /** The business name shown on the customer's phone for this paybill; empty if not set. */
    public String paybillMerchantName() { return orEmpty(current().getPaybillMerchantName()); }

    /** The admin-set account-number template, else the environment fallback. */
    public String paybillAccountTemplate() {
        String fromAdmin = current().getPaybillAccountTemplate();
        return fromAdmin == null || fromAdmin.isBlank() ? envAccountTemplate : fromAdmin;
    }

    /**
     * The account string the customer types after the paybill number, for a real order. Any
     * placeholder that has no value (an old order with no name, say) resolves to nothing rather
     * than leaving a literal "&lt;name&gt;" for a customer to type.
     */
    public String paybillAccountFor(String customerName, String phone, String orderNo, String reference) {
        return orderNumberIfBlank(resolve(paybillAccountTemplate(), customerName, phone, orderNo, reference),
                orderNo, reference);
    }

    /**
     * A customer must never be told to type an empty account number. A name written entirely in a
     * non-Latin script has nothing left after {@link #accountSafeName} strips it to what a paybill
     * accepts, so the template can come out blank; the order number (or, failing that, the payment
     * reference) is then the account.
     */
    static String orderNumberIfBlank(String resolved, String orderNo, String reference) {
        if (!resolved.isBlank()) {
            return resolved;
        }
        if (orderNo != null && !orderNo.isBlank()) {
            return orderNo;
        }
        return reference == null ? "" : reference;
    }

    /**
     * Before an order exists there is no order number or payment reference yet, so those two are shown
     * as words describing what will appear; {@code finalValue} tells the caller whether the string is
     * exactly what the customer will type (true when the template only uses name/phone).
     */
    public AccountPreview previewAccount(String customerName, String phone) {
        String template = paybillAccountTemplate();
        boolean needsOrder = template.contains("<order>") || template.contains("<ref>");
        String shown = resolve(template.replace("<order>", "YOUR ORDER NUMBER").replace("<ref>", "YOUR PAYMENT REFERENCE"),
                customerName, phone, "", "");
        return new AccountPreview(shown, !needsOrder);
    }

    public record AccountPreview(String accountNumber, boolean finalValue) {}

    /** Rejects a template with a placeholder that isn't one of {@link #ACCOUNT_PLACEHOLDERS} (a typo would reach customers verbatim). */
    public static void requireKnownPlaceholders(String template) {
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            if (!ACCOUNT_PLACEHOLDERS.contains(m.group())) {
                throw new com.nbh.backend.common.exception.UnprocessableException(
                        "Unknown placeholder " + m.group() + " in the account number. Use <name>, <phone>, <order> or <ref>.");
            }
        }
    }

    static String resolve(String template, String customerName, String phone, String orderNo, String reference) {
        return template
                .replace("<name>", accountSafeName(customerName))
                .replace("<phone>", phone == null ? "" : PhoneNumbers.normalize(phone))
                .replace("<order>", orderNo == null ? "" : orderNo)
                .replace("<ref>", reference == null ? "" : reference)
                .trim();
    }

    /** Upper-case letters, digits and single spaces only — punctuation in a name can make a paybill reject the account. */
    static String accountSafeName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() <= NAME_MAX ? cleaned : cleaned.substring(0, NAME_MAX).trim();
    }

    public String accountName() { return orEmpty(current().getAccountName()); }

    public String accountNumber() { return orEmpty(current().getAccountNumber()); }

    public String accountBranch() { return orEmpty(current().getAccountBranch()); }

    public String supportWhatsapp() { return orEmpty(current().getSupportWhatsapp()); }

    /** Spring Data's own generated query methods already run in their own read-only transaction. */
    private PaymentSettings current() {
        // §4 C7: a missing seeded row is a deployment problem, not a customer/admin input problem.
        return settingsRepository.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(() -> new ConfigurationException("Payment settings have not been seeded"));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
