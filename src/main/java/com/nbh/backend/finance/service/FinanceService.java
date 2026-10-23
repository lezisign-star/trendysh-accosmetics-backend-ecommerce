package com.nbh.backend.finance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.finance.dto.FinanceDtos.ByMethod;
import com.nbh.backend.finance.dto.FinanceDtos.Day;
import com.nbh.backend.finance.dto.FinanceDtos.LedgerEntry;
import com.nbh.backend.finance.dto.FinanceDtos.Lost;
import com.nbh.backend.finance.dto.FinanceDtos.Part;
import com.nbh.backend.finance.dto.FinanceDtos.PriceChanges;
import com.nbh.backend.finance.dto.FinanceDtos.PriceHistoryEntry;
import com.nbh.backend.finance.dto.FinanceDtos.Received;
import com.nbh.backend.finance.dto.FinanceDtos.Sales;
import com.nbh.backend.finance.dto.FinanceDtos.Split;
import com.nbh.backend.finance.dto.FinanceDtos.Step;
import com.nbh.backend.finance.dto.FinanceDtos.Summary;
import com.nbh.backend.finance.dto.FinanceDtos.Trail;
import com.nbh.backend.finance.dto.FinanceDtos.Waiting;
import com.nbh.backend.finance.repository.FinanceRepository;
import com.nbh.backend.finance.repository.FinanceRepository.LedgerRow;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderPriceChange;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.repository.OrderPriceChangeRepository;
import com.nbh.backend.payment.entity.BankCredit;
import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.BankCreditRepository;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.tumaboda.TumaBodaSettlement;
import com.nbh.backend.tumaboda.TumaBodaSettlementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * How much the website has made over a period, every payment with the path it took, and the life of one order's money
 * (owner, 2026-10-23: "tell how much has been made from the website for a period of time ... flow of money needs a way to
 * track it from the customers to the business and its path/data"). Read-only; it explains what the shop's own records say
 * and changes nothing.
 */
@Service
public class FinanceService {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    /** A report covers at most this long: a year and a bit, which is more than anyone reads at once. */
    static final Duration MAX_PERIOD = Duration.ofDays(400);
    /** The most rows a CSV export holds. */
    static final int MAX_EXPORT_ROWS = 10_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(NAIROBI);
    private static final Set<String> METHODS = Set.of("MPESA", "BANK", "CASH", "CARD");
    private static final Set<String> SOURCES = Set.of("ALL", "STAFF", "CUSTOMER");

    private final FinanceRepository finance;
    private final CustomerOrderRepository orders;
    private final PaymentRecordRepository payments;
    private final PaymentClaimRepository claims;
    private final BankCreditRepository bankCredits;
    private final OrderPriceChangeRepository priceChanges;
    private final TumaBodaSettlementRepository settlements;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FinanceService(FinanceRepository finance, CustomerOrderRepository orders, PaymentRecordRepository payments,
                          PaymentClaimRepository claims, BankCreditRepository bankCredits,
                          OrderPriceChangeRepository priceChanges, TumaBodaSettlementRepository settlements,
                          ObjectMapper objectMapper, Clock clock) {
        this.finance = finance;
        this.orders = orders;
        this.payments = payments;
        this.claims = claims;
        this.bankCredits = bankCredits;
        this.priceChanges = priceChanges;
        this.settlements = settlements;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** A period as asked for, with the defaults filled in (the last 30 days) and the limits applied. */
    public record Period(Instant from, Instant to) {}

    public Period period(Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now(clock).plusSeconds(60);
        Instant start = from != null ? from : end.minus(Duration.ofDays(30));
        if (!start.isBefore(end)) {
            throw new UnprocessableException("The period must start before it ends.");
        }
        if (Duration.between(start, end).compareTo(MAX_PERIOD) > 0) {
            throw new UnprocessableException("Choose a period of 400 days or less.");
        }
        return new Period(start, end);
    }

    @Transactional(readOnly = true)
    public Summary summary(Instant from, Instant to) {
        Period p = period(from, to);
        var totals = finance.totals(p.from(), p.to());
        List<ByMethod> methods = finance.byMethod(p.from(), p.to()).stream()
                .map(m -> new ByMethod(m.getMethod(), m.getPayments(), m.getAmount())).toList();
        var waiting = finance.waitingForPayment();
        var changes = finance.priceChanges(p.from(), p.to());
        List<Lost> lost = finance.notCompleted(p.from(), p.to()).stream()
                .map(l -> new Lost(l.getStatus(), l.getOrders(), l.getAmount())).toList();
        List<Day> daily = finance.byDay(p.from(), p.to()).stream()
                .map(d -> new Day(d.getDay().toLocalDate(), d.getOrders(), d.getReceived(), d.getStaffReceived())).toList();
        return new Summary(p.from(), p.to(),
                new Received(totals.getReceived(), totals.getOrders(), totals.getCancelledAfterPaid(), methods),
                new Sales(totals.getProducts(), totals.getDelivery()),
                new Split(new Part(totals.getOrders() - totals.getStaffOrders(),
                                totals.getReceived().subtract(totals.getStaffReceived())),
                        new Part(totals.getStaffOrders(), totals.getStaffReceived())),
                new Waiting(waiting.getOrders(), waiting.getAmount()), lost,
                new PriceChanges(changes.getChanges(), changes.getGivenAway(), changes.getRaised(),
                        changes.getWaiting(), changes.getDisputed()),
                daily);
    }

    /** Payments received in the period, newest first. {@code method}, {@code source} and {@code q} are optional filters. */
    @Transactional(readOnly = true)
    public Page<LedgerEntry> ledger(Instant from, Instant to, String method, String source, String q, Pageable pageable) {
        Period p = period(from, to);
        String query = q == null ? "" : q.trim();
        // % and _ typed by a person are letters to find, not wildcards.
        String escaped = query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = query.isEmpty() ? "" : "%" + escaped + "%";
        return finance.ledger(p.from(), p.to(), cleanMethod(method), cleanSource(source), query, like, phoneLike(query),
                        pageable)
                .map(this::entry);
    }

    /** What never matches a stored phone number: the search had none in it. */
    static final String NO_PHONE = "no-phone-in-the-search";

    /**
     * The pattern a customer's phone is matched with, only when the search IS a phone number (digits, with the spaces,
     * dashes, brackets or + people type): the digits of a payment reference or an order number ("QW1A2B3C4D") must not
     * match every phone that happens to hold "1234". The number as stored is 254712…, and staff type 0712…: the leading 0
     * is dropped and the rest matched anywhere. At least four digits, so "07" does not list every customer.
     */
    static String phoneLike(String query) {
        if (!query.matches("[\\d\\s+()\\-]+")) {
            return NO_PHONE;
        }
        String digits = query.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return NO_PHONE;
        }
        return "%" + (digits.startsWith("0") ? digits.substring(1) : digits) + "%";
    }

    /** Changes of normal selling prices in the period, newest first, kept automatically with who and when. */
    @Transactional(readOnly = true)
    public Page<PriceHistoryEntry> priceHistory(Instant from, Instant to, String q, Pageable pageable) {
        Period p = period(from, to);
        String query = q == null ? "" : q.trim();
        String escaped = query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return finance.priceHistory(p.from(), p.to(), query, query.isEmpty() ? "" : "%" + escaped + "%", pageable)
                .map(r -> new PriceHistoryEntry(r.getVariantId(), r.getProductName(),
                        ProductVariant.shortOptionOf(r.getOptionLabel(), r.getVariantName(), r.getProductName()),
                        r.getSku(), r.getOldPrice(), r.getNewPrice(), r.getChangedBy(),
                        Instant.ofEpochMilli(r.getChangedAtMillis())));
    }

    private static String cleanMethod(String method) {
        if (method == null || method.isBlank()) {
            return "";
        }
        String upper = method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(upper)) {
            throw new UnprocessableException("Unknown way of payment: " + method);
        }
        return upper;
    }

    private static String cleanSource(String source) {
        if (source == null || source.isBlank()) {
            return "ALL";
        }
        String upper = source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(upper)) {
            throw new UnprocessableException("Unknown source: " + source);
        }
        return upper;
    }

    private LedgerEntry entry(LedgerRow r) {
        String[] how = how(r);
        return new LedgerEntry(r.getPaymentId(), r.getOrderId(), r.getOrderNo(), Instant.ofEpochMilli(r.getPaidAtMillis()),
                r.getCustomerName(), r.getCustomerPhone(), r.getMethod(), r.getAmount(), r.getReference(),
                r.getProducts(), r.getDelivery(), r.getPlacedBy(), how[0], how[1], r.getClaimCode(), r.getBankTxnId(),
                r.getPriceChanges(), r.getAmountOff());
    }

    /**
     * What made a payment count, and who: {@code [how, by]}, by null when the record does not say. A payment confirmed after
     * it had been marked failed says so.
     */
    private static String[] how(LedgerRow r) {
        String[] how = howConfirmed(r);
        return r.getRevived() ? new String[]{how[0] + " (after it was marked failed)", how[1]} : how;
    }

    private static String[] howConfirmed(LedgerRow r) {
        String type = r.getConfirmType();
        if ("staff_entered_order".equals(type)) {
            return new String[]{"Taken by staff with the order", r.getConfirmBy()};
        }
        if ("manual_staff_confirmation".equals(type)) {
            return new String[]{"Confirmed by staff", r.getConfirmBy()};
        }
        if ("claim_approved".equals(type)) {
            return new String[]{"Customer's code approved by staff", r.getClaimResolvedBy()};
        }
        if ("bank_credit".equals(type)) {
            String by = r.getBankResolvedBy();
            return new String[]{"Matched to a bank credit", "system".equals(by) ? "automatically, on the reference" : by};
        }
        if (r.getMpesaCallback()) {
            return new String[]{"M-PESA confirmation", "Safaricom, automatically"};
        }
        return new String[]{"Confirmed", null};
    }

    /**
     * The ledger as a CSV file (Excel opens it): one line per payment. More than {@link #MAX_EXPORT_ROWS} is refused rather
     * than cut short, so a total added up from the file is never quietly less than the period's.
     */
    @Transactional(readOnly = true)
    public String ledgerCsv(Instant from, Instant to, String method, String source, String q) {
        Page<LedgerEntry> page = ledger(from, to, method, source, q, PageRequest.of(0, MAX_EXPORT_ROWS));
        if (page.getTotalElements() > MAX_EXPORT_ROWS) {
            throw new UnprocessableException("That is " + page.getTotalElements() + " payments, more than one spreadsheet"
                    + " holds (" + MAX_EXPORT_ROWS + "). Choose a shorter period or a filter.");
        }
        List<LedgerEntry> rows = page.getContent();
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Paid at (Nairobi),Order,Customer,Phone,Way of payment,Amount (KSh),Reference,Products (KSh),")
                .append("Delivery fee (KSh),Entered by,How it was confirmed,Confirmed by,Customer's code,Bank transaction,")
                .append("Price changes,Given away (KSh)\r\n");
        for (LedgerEntry e : rows) {
            csv.append(String.join(",",
                    text(STAMP.format(e.paidAt())), text(e.orderNo()), text(e.customerName()), text(phone(e.customerPhone())),
                    text(e.method()), number(e.amount()), text(e.reference()), number(e.products()), number(e.delivery()),
                    text(e.placedBy()), text(e.confirmedHow()), text(e.confirmedBy()), text(e.customerCode()),
                    text(e.bankTransaction()), String.valueOf(e.priceChanges()), number(e.amountOff())))
                    .append("\r\n");
        }
        return csv.toString();
    }

    /**
     * A phone as people write it ("0712 345 678"): stored as 254712345678, a bare number Excel would show as 2.54712E+11 and
     * lose the digits of once the file is saved again. Anything else is left as it is.
     */
    static String phone(String stored) {
        if (stored != null && stored.matches("254[17]\\d{8}")) {
            String local = "0" + stored.substring(3);
            return local.substring(0, 4) + " " + local.substring(4, 7) + " " + local.substring(7);
        }
        return stored;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    /**
     * A text cell. Names and references come from customers and staff, so a cell that starts with = + - or @ (which a
     * spreadsheet would run as a formula) gets a quote in front, and anything with a comma, quote or line break is quoted.
     */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        if ("=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ---- the life of one order's money ----

    @Transactional(readOnly = true)
    public Trail trail(UUID orderId) {
        CustomerOrder order = orders.findById(orderId).orElseThrow(() -> new NotFoundException("No such order"));
        List<PaymentRecord> paymentRecords = payments.findByOrderId(orderId).stream()
                .sorted(Comparator.comparing(PaymentRecord::getCreatedAt)).toList();
        List<PaymentClaim> orderClaims = claims.findByOrderIdNewestFirst(orderId).stream()
                .sorted(Comparator.comparing(PaymentClaim::getCreatedAt)).toList();
        List<BankCredit> credits = paymentRecords.isEmpty() ? List.of()
                : bankCredits.findByPaymentIdInOrderByReceivedAtAsc(
                        paymentRecords.stream().map(PaymentRecord::getId).collect(Collectors.toList()));
        List<OrderPriceChange> changes = priceChanges.findByOrderId(orderId);

        List<Step> steps = new ArrayList<>();
        steps.add(new Step(order.getCreatedAt(), "ORDER_PLACED",
                order.getPlacedBy() == null ? "Order placed by the customer on the website"
                        : "Order entered by " + order.getPlacedBy(),
                "Items " + ksh(order.getSubtotal()) + (order.getDeliveryFee().signum() > 0
                        ? " + delivery " + ksh(order.getDeliveryFee()) : "") + " = " + ksh(order.getGrandTotal())));

        for (OrderPriceChange c : changes) {
            steps.add(new Step(c.getCreatedAt(), "PRICE_CHANGE",
                    "Price changed on " + c.getLineLabel() + ": " + ksh(c.getListUnitPrice()) + " → "
                            + ksh(c.getNewUnitPrice()) + " each" + (c.getQty().compareTo(BigDecimal.ONE) == 0 ? ""
                            : " × " + c.getQty().stripTrailingZeros().toPlainString()),
                    "Reason: " + c.getReason() + ". Changed by " + c.getEnteredByName()
                            + (c.getConfirmerName() == null ? "" : ", agreed with " + c.getConfirmerName()
                            + (c.getWhatWasSaid() == null ? "" : " (\"" + c.getWhatWasSaid() + "\")"))
                            + "."));
            if (c.getAckAt() != null) {
                steps.add(new Step(c.getAckAt(), "PRICE_CHANGE_ANSWER",
                        c.getAckByName() + (c.getAckStatus() == OrderPriceChange.Acknowledgement.CONFIRMED
                                ? " confirmed" : " disputed") + " the price change on " + c.getLineLabel(),
                        c.getAckNote() == null ? null : "\"" + c.getAckNote() + "\""));
            }
        }

        for (PaymentRecord p : paymentRecords) {
            steps.add(new Step(p.getCreatedAt(), "PAYMENT_ASKED",
                    "Payment asked for: " + way(p.getMethod()) + " " + ksh(p.getAmount()),
                    p.getPaymentReference() == null ? null : "The customer quotes the reference " + p.getPaymentReference()));
        }

        for (PaymentClaim c : orderClaims) {
            steps.add(new Step(c.getCreatedAt(), "CUSTOMER_CODE",
                    "The customer sent the code " + c.getTransactionCode() + " for " + ksh(c.getAmountClaimed()),
                    c.getPayerName() == null ? null : "Name on the payment: " + c.getPayerName()));
            if (c.getResolvedAt() != null) {
                steps.add(new Step(c.getResolvedAt(), "CODE_ANSWER",
                        c.getResolvedBy() + (c.getStatus() == PaymentClaim.ClaimStatus.APPROVED ? " approved" : " rejected")
                                + " the code " + c.getTransactionCode(),
                        c.getResolutionNote()));
            }
        }

        for (BankCredit b : credits) {
            steps.add(new Step(b.getReceivedAt(), "BANK_CREDIT",
                    "The bank received " + ksh(b.getAmount())
                            + (b.getPayerName() == null ? "" : " from " + b.getPayerName()),
                    "Bank transaction " + b.getBankTxnId() + (b.getNarration() == null ? "" : ": " + b.getNarration())));
            if (b.getResolvedAt() != null) {
                steps.add(new Step(b.getResolvedAt(), "BANK_MATCH",
                        ("system".equals(b.getResolvedBy()) ? "Matched automatically" : "Matched by " + b.getResolvedBy())
                                + " to this order's payment",
                        b.getResolutionNote()));
            }
        }

        BigDecimal received = BigDecimal.ZERO;
        for (PaymentRecord p : paymentRecords) {
            if (p.getStatus() == PaymentRecord.PaymentStatus.SUCCESS) {
                received = received.add(p.getAmount());
                Instant when = order.getPlacedAt() != null ? order.getPlacedAt() : p.getUpdatedAt();
                steps.add(new Step(when, "PAYMENT_CONFIRMED",
                        "Payment confirmed: " + way(p.getMethod()) + " " + ksh(p.getAmount())
                                + (p.getProviderReference() == null ? "" : ", reference " + p.getProviderReference()),
                        confirmation(p)));
            } else if (p.getStatus() == PaymentRecord.PaymentStatus.FAILED) {
                steps.add(new Step(p.getUpdatedAt(), "PAYMENT_FAILED", "Payment marked failed", reasonOf(p)));
            } else if (p.getStatus() == PaymentRecord.PaymentStatus.REVERSED) {
                steps.add(new Step(p.getUpdatedAt(), "PAYMENT_REVERSED", "Payment reversed", null));
            }
        }

        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.EXPIRED) {
            steps.add(new Step(order.getUpdatedAt(), "ORDER_CLOSED",
                    order.getStatus() == OrderStatus.CANCELLED ? "Order cancelled" : "Order expired unpaid",
                    received.signum() > 0 ? "Money of " + ksh(received) + " had been received: check whether it was refunded." : null));
        }

        if (order.getTumabodaCost() != null) {
            TumaBodaSettlement s = order.getTumabodaSettlementId() == null ? null
                    : settlements.findById(order.getTumabodaSettlementId()).orElse(null);
            if (s != null) {
                steps.add(new Step(s.getSettledAt().atStartOfDay(NAIROBI).toInstant(), "DELIVERY_PAYOUT",
                        "Delivery paid out to TumaBoda: " + ksh(order.getTumabodaCost()),
                        "Settlement" + (s.getReference() == null ? "" : " " + s.getReference())));
            } else {
                steps.add(new Step(null, "DELIVERY_PAYOUT",
                        "Delivery of " + ksh(order.getTumabodaCost()) + " owed to TumaBoda, not paid out yet", null));
            }
        }

        // Oldest first; a step with no date (money still owed) goes last.
        steps.sort(Comparator.comparing(Step::at, Comparator.nullsLast(Comparator.naturalOrder())));
        boolean closed = order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.EXPIRED;
        BigDecimal owed = closed ? BigDecimal.ZERO : order.getGrandTotal().subtract(received).max(BigDecimal.ZERO);
        return new Trail(order.getId(), order.getOrderNo(), order.getStatus().name(), order.getCustomerName(),
                order.getGuestPhone(), order.getPlacedBy(), order.getSubtotal(), order.getDeliveryFee(),
                order.getGrandTotal(), received, owed, steps);
    }

    private static String ksh(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return "KSh " + String.format(Locale.ENGLISH, "%,.2f", value).replaceAll("\\.00$", "");
    }

    private static String way(PaymentRecord.PaymentMethod method) {
        return switch (method) {
            case MPESA -> "M-PESA";
            case BANK -> "bank transfer";
            case CASH -> "cash";
            case CARD -> "card";
        };
    }

    /**
     * How a successful payment was confirmed and by whom, from the note kept with it. A payment confirmed after it had been
     * marked failed keeps the confirmation's note under "now" (see PaymentService.confirmSuccess) and says so.
     */
    private String confirmation(PaymentRecord p) {
        JsonNode kept = json(p.getRawResponse());
        if (kept != null && "revived_after_failure".equals(text(kept, "type"))) {
            JsonNode now = kept.get("now");
            String how = confirmationFrom(now == null || now.isNull() ? null : now);
            return (how == null ? "Confirmed" : how) + " after it had been marked failed";
        }
        return confirmationFrom(kept);
    }

    private String confirmationFrom(JsonNode note) {
        String type = note == null ? null : text(note, "type");
        String by = note == null ? null : text(note, "by");
        if ("staff_entered_order".equals(type)) {
            return "Taken by " + (by == null ? "staff" : by) + " while entering the order"
                    + (text(note, "notes") == null ? "" : ": " + text(note, "notes"));
        }
        if ("manual_staff_confirmation".equals(type)) {
            return "Confirmed by " + (by == null ? "staff" : by)
                    + (text(note, "notes") == null ? "" : ": " + text(note, "notes"));
        }
        if ("claim_approved".equals(type)) {
            return "Confirmed by approving the customer's code";
        }
        if ("bank_credit".equals(type)) {
            return "Confirmed by matching a bank credit";
        }
        if (note != null && note.has("Body")) {
            return "Confirmed by Safaricom's M-PESA callback";
        }
        return null;
    }

    private String reasonOf(PaymentRecord p) {
        JsonNode note = json(p.getRawResponse());
        return note == null || text(note, "reason") == null ? null : "Reason: " + text(note, "reason");
    }

    private JsonNode json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
