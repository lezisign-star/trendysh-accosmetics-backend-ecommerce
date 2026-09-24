package com.nbh.backend.payment.coop;

import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.service.BankCreditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a Co-op INS notification into a {@link BankCredit}. Only CREDIT events for our account are recorded; the
 * result is deliberately lenient about shape (the bank's real payload has not been seen yet) and strict about
 * effect (idempotent on the bank's transaction id, and auto-matching is off unless explicitly enabled).
 */
@Slf4j
@Service
public class CoopInsService {

    public enum Outcome { RECORDED, IGNORED }

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BankCreditService creditService;
    private final CoopInsSettings settings;
    private final Clock clock;

    public CoopInsService(BankCreditService creditService, CoopInsSettings settings, Clock clock) {
        this.creditService = creditService;
        this.settings = settings;
        this.clock = clock;
    }

    public Outcome handle(CoopInsNotification n) {
        if (n == null) {
            log.warn("Co-op INS: empty body ignored");
            return Outcome.IGNORED;
        }
        if (!"CREDIT".equalsIgnoreCase(blankToEmpty(n.eventType()))) {
            log.info("Co-op INS: non-credit event '{}' ignored (ref {})", n.eventType(), n.messageReference());
            return Outcome.IGNORED;
        }
        if (!settings.accountNumber().isBlank() && !settings.accountNumber().equals(blankToEmpty(n.accountNumber()))) {
            log.warn("Co-op INS: credit for a different account ({}) ignored", n.accountNumber());
            return Outcome.IGNORED;
        }
        BigDecimal amount = n.amount();
        if (amount == null || amount.signum() <= 0) {
            log.warn("Co-op INS: credit with missing or non-positive amount ignored (ref {})", n.messageReference());
            return Outcome.IGNORED;
        }
        String txnId = firstNonBlank(n.transactionId(), n.paymentRef(), n.messageReference());
        if (txnId == null) {
            log.warn("Co-op INS: credit with no transaction id ignored");
            return Outcome.IGNORED;
        }

        creditService.record(txnId, amount, null, null, narrationText(n), receivedAt(n),
                BankCredit.Source.IPN, settings.autoMatch());
        return Outcome.RECORDED;
    }

    /**
     * The customer's reference could arrive in the narration or in any memo line; the spec does not say which
     * (unconfirmed with the bank), so all of them are searched for a valid reference.
     */
    private static String narrationText(CoopInsNotification n) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, n.narration());
        if (n.custMemo() != null) {
            addIfPresent(parts, n.custMemo().line1());
            addIfPresent(parts, n.custMemo().line2());
            addIfPresent(parts, n.custMemo().line3());
        }
        return String.join(" | ", parts);
    }

    private Instant receivedAt(CoopInsNotification n) {
        String raw = n.transactionDate();
        try {
            if (raw != null && raw.length() == 14) {
                return LocalDateTime.parse(raw, DATE_TIME).atZone(NAIROBI).toInstant();
            }
            if (raw != null && raw.length() == 8) {
                return LocalDate.parse(raw, DATE_ONLY).atStartOfDay(NAIROBI).toInstant();
            }
        } catch (DateTimeParseException e) {
            log.warn("Co-op INS: unparseable TransactionDate '{}', using receipt time", raw);
        }
        return Instant.now(clock);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
