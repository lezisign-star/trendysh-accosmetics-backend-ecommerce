package com.nbh.backend.order.service;

import com.nbh.backend.common.email.EmailTemplates;
import com.nbh.backend.common.event.CustomerEmailRequestedEvent;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.common.exception.UnauthorizedException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.common.util.Sha256;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.dto.TrackingDtos.OrderStatusDto;
import com.nbh.backend.order.dto.TrackingDtos.SessionDto;
import com.nbh.backend.order.dto.TrackingDtos.TrackedOrderSummaryDto;
import com.nbh.backend.order.entity.OrderTrackingCode;
import com.nbh.backend.order.entity.OrderTrackingSession;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.order.repository.OrderTrackingCodeRepository;
import com.nbh.backend.order.repository.OrderTrackingSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Guest order lookup. An order NUMBER on its own reveals only a status. To see the orders under an
 * EMAIL, the guest first proves the mailbox: a 6-digit code is emailed, and entering it buys a short
 * lookup session whose token lists that email's orders and opens any one of them. Orders bought
 * while signed in are still reachable this way, since the checkout email is stored on every order.
 *
 * <p>Mirrors the customer password-reset OTP ({@code CustomerAuthService}): only SHA-256 hashes are
 * stored, a code is single use, and five wrong guesses burn it.
 */
@Service
public class OrderTrackingService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration SESSION_TTL = Duration.ofMinutes(30);
    static final int MAX_ATTEMPTS = 5;
    private static final String GENERIC_INVALID = "That code isn't right or has expired. Please request a new one.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CustomerOrderRepository orders;
    private final OrderTrackingCodeRepository codes;
    private final OrderTrackingSessionRepository sessions;
    private final TrackingCodeThrottle throttle;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OrderFulfilmentService fulfilment;
    private final com.nbh.backend.catalog.service.ProductReviewService reviews;
    private final String frontendUrl;

    public OrderTrackingService(CustomerOrderRepository orders, OrderTrackingCodeRepository codes,
                                OrderTrackingSessionRepository sessions, TrackingCodeThrottle throttle,
                                ApplicationEventPublisher events, Clock clock,
                                OrderFulfilmentService fulfilment,
                                com.nbh.backend.catalog.service.ProductReviewService reviews,
                                @Value("${app.frontend-url:http://localhost:5250}") String frontendUrl) {
        this.orders = orders;
        this.codes = codes;
        this.sessions = sessions;
        this.throttle = throttle;
        this.events = events;
        this.clock = clock;
        this.fulfilment = fulfilment;
        this.reviews = reviews;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** Order number alone: the status only. */
    @Transactional(readOnly = true)
    public OrderStatusDto status(String rawOrderNo) {
        String orderNo = normalizeOrderNo(rawOrderNo);
        return orders.findStatusByOrderNo(orderNo)
                .map(status -> new OrderStatusDto(orderNo, status))
                .orElseThrow(() -> new NotFoundException("We couldn't find an order with that number"));
    }

    /**
     * The canonical form of an order number that really exists, for the live-updates stream: it must
     * refuse an unknown number the same way {@link #status} does, rather than silently opening a
     * connection that will never hear anything.
     */
    @Transactional(readOnly = true)
    public String verifiedOrderNo(String rawOrderNo) {
        String orderNo = normalizeOrderNo(rawOrderNo);
        if (orders.findStatusByOrderNo(orderNo).isEmpty()) {
            throw new NotFoundException("We couldn't find an order with that number");
        }
        return orderNo;
    }

    /** Always succeeds from the caller's side: never reveals whether the email has orders. */
    @Transactional
    public void requestCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        throttle.checkAndRecord(email);
        if (!orders.existsByGuestEmailIgnoreCase(email)) {
            return;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        OrderTrackingCode row = new OrderTrackingCode();
        row.setEmail(email);
        row.setCodeHash(Sha256.hex(code));
        row.setExpiresAt(Instant.now(clock).plus(CODE_TTL));
        codes.save(row);

        String intro = "Someone (hopefully you) asked to see the orders placed with this email at Trendysh. "
                + "Your verification code is below.";
        events.publishEvent(new CustomerEmailRequestedEvent(email, "Your Trendysh order lookup code",
                "Hi there,\n\n" + intro + "\n\n" + code
                        + "\n\nIt expires in 10 minutes. If you didn't ask for this, you can ignore this email — nothing changes.",
                EmailTemplates.otpCode(frontendUrl + "/trendysh-logo.png", "there", intro, code, 10)));
    }

    // noRollbackFor: a wrong-code guess must still commit its attempts increment even though we then throw.
    @Transactional(noRollbackFor = UnprocessableException.class)
    public SessionDto verify(String rawEmail, String rawCode) {
        String email = normalizeEmail(rawEmail);
        OrderTrackingCode row = codes.findFirstByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new UnprocessableException(GENERIC_INVALID));
        Instant now = Instant.now(clock);
        if (row.getUsedAt() != null || !row.getExpiresAt().isAfter(now)) {
            throw new UnprocessableException(GENERIC_INVALID);
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.setUsedAt(now);
            codes.save(row);
            throw new UnprocessableException("Too many incorrect attempts. Please request a new code.");
        }
        if (!Sha256.hex(rawCode.trim()).equals(row.getCodeHash())) {
            row.setAttempts(row.getAttempts() + 1);
            codes.save(row);
            int left = MAX_ATTEMPTS - row.getAttempts();
            throw new UnprocessableException(left > 0
                    ? "Incorrect code. " + left + " attempt" + (left == 1 ? "" : "s") + " left."
                    : "Too many incorrect attempts. Please request a new code.");
        }
        row.setUsedAt(now);
        codes.save(row);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OrderTrackingSession session = new OrderTrackingSession();
        session.setEmail(email);
        session.setTokenHash(Sha256.hex(token));
        session.setExpiresAt(now.plus(SESSION_TTL));
        sessions.save(session);
        return new SessionDto(token, session.getExpiresAt());
    }

    /** The orders under the verified email, newest first; {@code orderNo} (optional) narrows to that one order. */
    @Transactional(readOnly = true)
    public List<TrackedOrderSummaryDto> orders(String token, String orderNo) {
        String email = emailFor(token);
        List<TrackedOrderSummaryDto> all = orders.findTrackedSummaries(email);
        if (orderNo == null || orderNo.isBlank()) {
            return all;
        }
        String wanted = normalizeOrderNo(orderNo);
        return all.stream().filter(o -> o.orderNo().equalsIgnoreCase(wanted)).toList();
    }

    /** Full detail for one of the verified email's orders; someone else's order is reported as not found. */
    @Transactional(readOnly = true)
    public OrderDto order(String token, String rawOrderNo) {
        String email = emailFor(token);
        return orders.findByOrderNo(normalizeOrderNo(rawOrderNo))
                .filter(o -> o.getGuestEmail().equalsIgnoreCase(email))
                .map(OrderDto::from)
                .orElseThrow(() -> new NotFoundException("No order with that number under this email"));
    }

    /**
     * The customer confirms their dispatched order arrived (DISPATCHED -> DELIVERED). Only the verified
     * email's own orders qualify; confirming twice is harmless (an already-delivered order is returned
     * as is), and an order that hasn't been dispatched yet is refused.
     */
    @Transactional
    public OrderDto confirmReceived(String token, String rawOrderNo) {
        String email = emailFor(token);
        CustomerOrder order = orders.findByOrderNo(normalizeOrderNo(rawOrderNo))
                .filter(o -> o.getGuestEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new NotFoundException("No order with that number under this email"));
        if (order.getStatus() == OrderStatus.DELIVERED) {
            return OrderDto.from(order);
        }
        return OrderDto.from(fulfilment.markDelivered(order.getId()));
    }

    /** A review of one product on the verified email's own order; goes to staff to approve before it shows. */
    @Transactional
    public void review(String token, String rawOrderNo, com.nbh.backend.order.dto.TrackingDtos.TrackedReviewRequest request) {
        String email = emailFor(token);
        CustomerOrder order = orders.findByOrderNo(normalizeOrderNo(rawOrderNo))
                .filter(o -> o.getGuestEmail().equalsIgnoreCase(email))
                .orElseThrow(() -> new NotFoundException("No order with that number under this email"));
        reviews.submitForVerifiedOrder(order, request.productId(), request.rating(), request.comment(),
                request.reviewerName());
    }

    private String emailFor(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Please verify your email to see your orders");
        }
        Instant now = Instant.now(clock);
        return sessions.findByTokenHash(Sha256.hex(token.trim()))
                .filter(s -> s.getExpiresAt().isAfter(now))
                .map(OrderTrackingSession::getEmail)
                .orElseThrow(() -> new UnauthorizedException("Your lookup has expired. Please verify your email again."));
    }

    /** Spent codes and lapsed sessions have no further use. */
    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(1));
        codes.deleteExpiredBefore(cutoff);
        sessions.deleteExpiredBefore(cutoff);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOrderNo(String orderNo) {
        return orderNo.trim().toUpperCase(Locale.ROOT);
    }
}
