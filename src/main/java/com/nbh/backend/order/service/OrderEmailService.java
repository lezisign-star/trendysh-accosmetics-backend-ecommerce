package com.nbh.backend.order.service;

import com.nbh.backend.common.email.EmailTemplates;
import com.nbh.backend.common.email.EmailTemplates.Fact;
import com.nbh.backend.common.email.EmailTemplates.Line;
import com.nbh.backend.common.event.CustomerEmailRequestedEvent;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.dto.PaymentInstructionsDto;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentInstructionsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The emails about an order (owner's decision, 2026-10-06): one when it is placed, with the way to pay and the link back
 * to the page that takes the M-PESA code; one reminder if that code has not arrived after a while; and a receipt when
 * the payment is confirmed. Before this the shop sent no order emails at all.
 *
 * <p>An email is requested as a {@link CustomerEmailRequestedEvent}, so it only goes out once the transaction that
 * asked for it has committed: nobody is emailed about an order that then failed to save. Without a mail provider
 * configured (no {@code BREVO_API_KEY}) it is only written to the log, as for every other email the shop sends.
 *
 * <p>The placed and received emails run in a transaction of their own ({@code REQUIRES_NEW}): they are triggered after the
 * order's transaction has committed, from its events, and read the order afresh.
 */
@Service
public class OrderEmailService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(SHOP_ZONE);

    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentInstructionsService instructionsService;
    private final ApplicationEventPublisher events;
    private final String frontendUrl;

    public OrderEmailService(CustomerOrderRepository orderRepository,
                             PaymentRecordRepository paymentRecordRepository,
                             PaymentInstructionsService instructionsService,
                             ApplicationEventPublisher events,
                             @Value("${app.frontend-url:http://localhost:5250}") String frontendUrl) {
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.instructionsService = instructionsService;
        this.events = events;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** "Your order is saved, one more step": the way to pay and the link to type the M-PESA code. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void orderPlaced(UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId).orElse(null);
        PaymentRecord payment = unpaidBankPayment(orderId);
        if (order == null || payment == null || !emailable(order)) {
            return;
        }
        PaymentInstructionsDto instructions = instructionsService.build(payment);
        String firstName = firstName(order);
        String until = payment.getExpiresAt() == null ? null : CLOCK.format(payment.getExpiresAt());

        String intro = "Thank you for ordering from Trendysh. Your items are reserved"
                + (until == null ? "" : " until " + until)
                + ". Pay with M-PESA Paybill, then type the confirmation code M-PESA texts you on the payment page: "
                + "your order is complete once we have that code.";
        List<Fact> facts = payFacts(instructions);
        String payLink = payLink(order, payment);

        send(order, "Order " + order.getOrderNo() + ": pay with M-PESA to complete it",
                text(firstName, "Your order is saved, one more step", intro, facts, order, "Pay and enter your M-PESA code:", payLink,
                        "Already paid? Open that link and type the code from your M-PESA message. Questions? WhatsApp us on "
                                + instructions.supportWhatsapp() + "."),
                EmailTemplates.orderMessage(logoUrl(), firstName, "Your order is saved, one more step", intro, facts,
                        itemLines(order), "Total", ksh(order.getGrandTotal()), "Pay and enter my M-PESA code", payLink,
                        "Already paid? Open the link and type the code from your M-PESA message. Questions? WhatsApp us on "
                                + instructions.supportWhatsapp() + "."));
    }

    /**
     * The one reminder: the customer placed an order, has not entered the M-PESA code that completes it, and the stock
     * is still held for them. Called inside the reminder job's own transaction, with the order and payment it just locked.
     */
    public void paymentReminder(CustomerOrder order, PaymentRecord payment) {
        if (!emailable(order)) {
            return;
        }
        PaymentInstructionsDto instructions = instructionsService.build(payment);
        String firstName = firstName(order);
        String until = payment.getExpiresAt() == null ? null : CLOCK.format(payment.getExpiresAt());

        String intro = "You placed order " + order.getOrderNo() + " a little while ago, but we haven't received the M-PESA code "
                + "that completes it. If you've already paid, open the payment page and type the code from your M-PESA message. "
                + (until == null ? "" : "If you haven't paid yet, your items are reserved until " + until + ".");
        List<Fact> facts = payFacts(instructions);
        String payLink = payLink(order, payment);

        send(order, "Reminder: we still need your M-PESA code for order " + order.getOrderNo(),
                text(firstName, "We still need your M-PESA code", intro, facts, order, "Type your M-PESA code here:", payLink,
                        "Need help? WhatsApp us on " + instructions.supportWhatsapp() + "."),
                EmailTemplates.orderMessage(logoUrl(), firstName, "We still need your M-PESA code", intro, facts,
                        itemLines(order), "Total", ksh(order.getGrandTotal()), "Enter my M-PESA code", payLink,
                        "Need help? WhatsApp us on " + instructions.supportWhatsapp() + "."));
    }

    /** "Payment received": the receipt, with where the order is collected or how it will be delivered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void paymentReceived(UUID orderId) {
        CustomerOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || !emailable(order)) {
            return;
        }
        String firstName = firstName(order);
        String next = order.getFulfilmentType() == FulfilmentType.PICKUP
                ? "We're getting it ready and will update your order page when it can be collected"
                        + (order.getDeliveryAddress() == null ? "." : " at " + order.getDeliveryAddress() + ".")
                : "We're getting it ready and will call you to arrange the delivery.";
        String intro = "Thank you! We've received your payment of " + ksh(order.getGrandTotal()) + " for order "
                + order.getOrderNo() + ". " + next;
        String trackLink = frontendUrl + "/track?order=" + order.getOrderNo();

        send(order, "Payment received for order " + order.getOrderNo(),
                text(firstName, "Payment received", intro, List.of(), order, "Follow your order here:", trackLink, null),
                EmailTemplates.orderMessage(logoUrl(), firstName, "Payment received", intro, List.of(), itemLines(order),
                        "Total paid", ksh(order.getGrandTotal()), "Track my order", trackLink, null));
    }

    // ---- building blocks

    private PaymentRecord unpaidBankPayment(UUID orderId) {
        return paymentRecordRepository.findByOrderId(orderId).stream()
                .filter(p -> p.getPaymentReference() != null
                        && (p.getStatus() == PaymentRecord.PaymentStatus.INITIATED
                            || p.getStatus() == PaymentRecord.PaymentStatus.PENDING))
                .findFirst().orElse(null);
    }

    /** Demo-catalogue orders never get email, and an order with no address to send to has nobody to tell. */
    private static boolean emailable(CustomerOrder order) {
        return !order.isMockData() && order.getGuestEmail() != null && !order.getGuestEmail().isBlank();
    }

    private static List<Fact> payFacts(PaymentInstructionsDto instructions) {
        List<Fact> facts = new ArrayList<>();
        if (instructions.paybill() != null) {
            facts.add(new Fact("Business number", instructions.paybill().paybillNumber()));
            facts.add(new Fact("Account number", instructions.paybill().accountNumber()));
            facts.add(new Fact("Amount", ksh(instructions.amount())));
        }
        return facts;
    }

    private String payLink(CustomerOrder order, PaymentRecord payment) {
        return frontendUrl + "/pay/" + order.getOrderNo() + "?ref=" + payment.getPaymentReference();
    }

    private String logoUrl() {
        return frontendUrl + "/trendysh-logo.png";
    }

    /** What was ordered, one line each (a kit's own components are part of its line, so they are not listed again). */
    private static List<Line> itemLines(CustomerOrder order) {
        List<Line> lines = new ArrayList<>();
        for (CustomerOrderItem item : order.getItems()) {
            if (item.getParentItem() != null) {
                continue;
            }
            String name = item.getVariant().displayLabel()
                    + " × " + item.getQty().stripTrailingZeros().toPlainString();
            lines.add(new Line(name, ksh(item.getLineTotal())));
        }
        if (order.getDeliveryFee() != null && order.getDeliveryFee().signum() > 0) {
            lines.add(new Line("TumaBoda delivery", ksh(order.getDeliveryFee())));
        }
        return lines;
    }

    /** The plain-text version every email carries alongside its HTML. */
    private String text(String firstName, String headline, String intro, List<Fact> facts, CustomerOrder order,
                        String linkLead, String link, String note) {
        StringBuilder text = new StringBuilder("Hi ").append(firstName).append(",\n\n")
                .append(headline).append("\n\n").append(intro).append("\n\n");
        for (Fact fact : facts) {
            text.append(fact.label()).append(": ").append(fact.value()).append("\n");
        }
        if (!facts.isEmpty()) {
            text.append("\n");
        }
        for (Line line : itemLines(order)) {
            text.append("  ").append(line.description()).append("  ").append(line.amountText()).append("\n");
        }
        text.append("  Total  ").append(ksh(order.getGrandTotal())).append("\n\n")
                .append(linkLead).append("\n").append(link).append("\n");
        if (note != null) {
            text.append("\n").append(note).append("\n");
        }
        return text.append("\nTrendysh Accosmetic").toString();
    }

    private void send(CustomerOrder order, String subject, String textBody, String htmlBody) {
        events.publishEvent(new CustomerEmailRequestedEvent(order.getGuestEmail(), subject, textBody, htmlBody));
    }

    /**
     * A name a person could have: letters, digits, apostrophes and hyphens, at most 30. The checkout is public and the
     * name is whatever the sender typed, so a "first name" like www.refund-example.co.ke would otherwise open an email
     * from the shop's own address and be turned into a link by the reader's mail app. Anything else greets as "there".
     */
    private static final Pattern NAME_LIKE = Pattern.compile("[\\p{L}\\p{M}\\p{N}'’-]{1,30}");

    private static String firstName(CustomerOrder order) {
        String name = order.getCustomerName() == null ? "" : order.getCustomerName().trim();
        if (name.isEmpty()) {
            return "there";
        }
        String first = name.split("\\s+")[0];
        return NAME_LIKE.matcher(first).matches() ? first : "there";
    }

    /** "KSh 2,400", or "KSh 2,400.50" when there are cents. */
    static String ksh(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        boolean whole = value.stripTrailingZeros().scale() <= 0;
        return "KSh " + new DecimalFormat(whole ? "#,##0" : "#,##0.00").format(value);
    }

    /** When the stock hold ends, as a clock time in the shop's zone; used by tests. */
    static String clock(Instant instant) {
        return CLOCK.format(instant);
    }
}
