package com.nbh.backend.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.payment.dto.MpesaCallbackRequest;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.DarajaPaymentGateway;
import com.nbh.backend.payment.service.PaymentService;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two ways a payment gets confirmed today:
 *  1. {@link #initiateMpesa}/{@link #mpesaCallback} — the real Daraja STK push
 *     round-trip. NOT LIVE-TESTED (see DarajaPaymentGateway's javadoc) —
 *     calling initiateMpesa with the current unset placeholder credentials
 *     will fail loudly and clearly, not silently.
 *  2. {@link #confirmManual}/{@link #failManual} — a staff action for cash,
 *     bank transfer, or an M-Pesa till payment confirmed by phone. This is
 *     what makes the whole checkout-to-fulfilment loop testable RIGHT NOW,
 *     before real Daraja credentials exist, and stays useful afterwards for
 *     payment methods that don't have a webhook at all.
 * Both paths call the exact same {@link PaymentService} methods — there is
 * only one place an order actually moves out of PENDING_PAYMENT.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final DarajaPaymentGateway darajaPaymentGateway;
    // §4 C9: the app-wide bean (jackson-datatype-jsr310 registered, consistent
    // Instant serialisation) instead of a bare `new ObjectMapper()` — matters
    // here specifically because toJson()'s output is stored in payment_record's
    // jsonb columns, read back months later to settle a payment dispute.
    private final ObjectMapper objectMapper;

    /**
     * §4 C1 fix: a high-entropy path segment only Safaricom's registered
     * callback URL carries (the value baked into MPESA_CALLBACK_URL at
     * deployment time — see DarajaPaymentGateway's javadoc). Before this,
     * the endpoint was permitAll with no authenticity check of any kind, and
     * the value it trusted (CheckoutRequestID) was already in the customer's
     * own browser — so any customer could self-confirm their own unpaid order.
     * Blank (the default until this is set) means "reject everything", same
     * philosophy as DarajaPaymentGateway refusing to call Safaricom with blank
     * credentials.
     */
    @Value("${app.mpesa.callback-secret:}")
    private String callbackSecret;

    public PaymentController(PaymentService paymentService,
                              PaymentRecordRepository paymentRecordRepository,
                              DarajaPaymentGateway darajaPaymentGateway,
                              ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.darajaPaymentGateway = darajaPaymentGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Found live while verifying §4 C8 this session (never previously
     * exercised — Daraja has never had real credentials, and initiateMpesa
     * fails fast on that before reaching this line normally; blank-credential
     * testing surfaced it): {@code payment.getOrder()} is a lazy {@code
     * @ManyToOne}, and this method had no transaction boundary of its own —
     * {@code open-in-view: false} means the Hibernate session from {@code
     * findById} is already closed by the time {@code getGuestPhone()}/{@code
     * getOrderNo()} below try to initialise it, throwing
     * LazyInitializationException. Exact same bug class as audit finding F-
     * (session 3, StockController.balance()) and §4 C16 exists to catch
     * mechanically for controller methods that call a DTO's from(...) — this
     * one accesses the lazy association directly instead, so it wasn't
     * structurally visible to that rule. Fixed the same way as every other
     * instance of this bug in this codebase: @Transactional on the
     * controller method itself.
     */
    @PostMapping("/{paymentRecordId}/initiate-mpesa")
    @Transactional
    public Map<String, String> initiateMpesa(@PathVariable UUID paymentRecordId) {
        PaymentRecord payment = paymentRecordRepository.findById(paymentRecordId)
                .orElseThrow(() -> new EntityNotFoundException("No payment record: " + paymentRecordId));

        long wholeShillings = payment.getAmount().setScale(0, java.math.RoundingMode.CEILING).longValueExact();
        String checkoutRequestId = darajaPaymentGateway.initiateStkPush(
                payment.getOrder().getGuestPhone(), wholeShillings,
                payment.getOrder().getOrderNo(), "Nairobi Beauty Hub order " + payment.getOrder().getOrderNo());

        payment.setProviderReference(checkoutRequestId);
        payment.setStatus(PaymentRecord.PaymentStatus.PENDING);
        paymentRecordRepository.save(payment);

        return Map.of("checkoutRequestId", checkoutRequestId);
    }

    /**
     * Daraja's callback shape. §4 C1 fix — three changes from the original:
     *  1. Authenticated by a high-entropy secret segment in the URL itself
     *     (compared with {@link MessageDigest#isEqual}, not {@code equals},
     *     to avoid a timing side-channel), instead of trusting a value the
     *     customer's own browser already holds.
     *  2. Bound to a typed {@link MpesaCallbackRequest} instead of
     *     {@code Map<String,Object>} + unchecked casts, so a malformed or
     *     unexpected body cannot NPE into a 500 — it's logged and answered
     *     with Safaricom's expected 200/Accepted shape instead (Safaricom
     *     retries non-200 responses).
     *  3. Cross-checks the callback's own claimed Amount against the
     *     PaymentRecord's amount before ever confirming success — the
     *     callback's ResultCode/CheckoutRequestID alone is not trusted to
     *     mean "the right amount was paid".
     * Still NOT LIVE-TESTED — no real Safaricom callback has ever hit this.
     */
    @PostMapping("/mpesa/callback/{secret}")
    public ResponseEntity<Map<String, String>> mpesaCallback(
            @PathVariable String secret,
            @RequestBody(required = false) MpesaCallbackRequest callback) {

        if (!isAuthenticCallback(secret)) {
            log.warn("M-Pesa callback rejected: callback secret missing or did not match");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        MpesaCallbackRequest.StkCallback stkCallback =
                callback != null && callback.body() != null ? callback.body().stkCallback() : null;
        if (stkCallback == null || stkCallback.checkoutRequestId() == null || stkCallback.resultCode() == null) {
            log.warn("Malformed M-Pesa callback body received (missing stkCallback/CheckoutRequestID/ResultCode)");
            return accepted();
        }

        String checkoutRequestId = stkCallback.checkoutRequestId();
        PaymentRecord payment = paymentRecordRepository.findByProviderReference(checkoutRequestId).orElse(null);
        if (payment == null) {
            log.warn("M-Pesa callback for unknown CheckoutRequestID {}", checkoutRequestId);
            return accepted();
        }

        String raw = toJson(callback);
        try {
            if (stkCallback.resultCode() == 0) {
                BigDecimal expectedAmount = payment.getAmount().setScale(0, RoundingMode.CEILING);
                BigDecimal callbackAmount = extractAmount(stkCallback);
                if (callbackAmount == null || callbackAmount.compareTo(expectedAmount) != 0) {
                    log.warn("M-Pesa callback amount mismatch for payment {}: expected {}, callback reported {}",
                            payment.getId(), expectedAmount, callbackAmount);
                    paymentService.markFailed(payment.getId(),
                            "Callback amount mismatch (expected " + expectedAmount + ", got " + callbackAmount + ")", raw);
                    return accepted();
                }
                paymentService.confirmSuccess(payment.getId(), checkoutRequestId, raw);
            } else {
                paymentService.markFailed(payment.getId(), "Daraja ResultCode " + stkCallback.resultCode(), raw);
            }
        } catch (IllegalStateException e) {
            // A late/duplicate callback arriving after the payment already moved to
            // its terminal state the other way (e.g. a success retry arriving after
            // a staff manual-fail). Not an error Safaricom should retry over — log
            // and acknowledge, exactly like the idempotent early-return above.
            log.warn("M-Pesa callback for payment {} could not be applied: {}", payment.getId(), e.getMessage());
        } catch (ConflictException e) {
            // Money arrived but the order can't take it (its stock hold lapsed and the stock has
            // gone, or the order was cancelled). Retrying won't change that, so acknowledge — but
            // loudly: a customer has paid for something staff now have to resolve by hand.
            log.error("M-Pesa callback for payment {} was paid but could not be applied — needs staff: {}",
                    payment.getId(), e.getMessage());
        }
        return accepted();
    }

    /** Safaricom retries any non-200 response, so a rejected/malformed callback is still answered this way. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableCallback(HttpMessageNotReadableException e) {
        log.warn("Unreadable M-Pesa callback body: {}", e.getMessage());
        return accepted();
    }

    private ResponseEntity<Map<String, String>> accepted() {
        return ResponseEntity.ok(Map.of("ResultCode", "0", "ResultDesc", "Accepted"));
    }

    private boolean isAuthenticCallback(String secret) {
        if (callbackSecret == null || callbackSecret.isBlank() || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), callbackSecret.getBytes(StandardCharsets.UTF_8));
    }

    private BigDecimal extractAmount(MpesaCallbackRequest.StkCallback stkCallback) {
        if (stkCallback.callbackMetadata() == null || stkCallback.callbackMetadata().item() == null) {
            return null;
        }
        for (MpesaCallbackRequest.Item item : stkCallback.callbackMetadata().item()) {
            if ("Amount".equals(item.name()) && item.value() != null) {
                try {
                    return new BigDecimal(item.value().toString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    @PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{paymentRecordId}/confirm-manual")
    public void confirmManual(@PathVariable UUID paymentRecordId, @RequestBody ManualConfirmRequest request) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("type", "manual_staff_confirmation");
        note.put("notes", request.notes());
        paymentService.confirmSuccess(paymentRecordId, request.reference(), toJson(note), request.method());
    }

    @PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
    @PostMapping("/{paymentRecordId}/fail-manual")
    public void failManual(@PathVariable UUID paymentRecordId, @RequestBody ManualFailRequest request) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("type", "manual_staff_failure");
        note.put("reason", request.reason());
        paymentService.markFailed(paymentRecordId, request.reason(), toJson(note));
    }

    /** rawRequest/rawResponse are mapped as jsonb — every caller must go through here, never a bare toString(). */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise payment record detail to JSON", e);
        }
    }

    /** {@code method} optional: how the customer actually paid (MPESA/BANK/CASH); omitted keeps the record's own. */
    public record ManualConfirmRequest(String reference, String notes, PaymentRecord.PaymentMethod method) {}

    public record ManualFailRequest(String reason) {}
}
