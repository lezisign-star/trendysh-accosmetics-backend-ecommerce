package com.nbh.backend.order.service;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Moves an unpaid order to EXPIRED once none of its stock holds is still live.
 *
 * <p>Until 2026-09-28 nothing did: {@code StockReservationExpirySweeper} correctly gave a lapsed
 * hold's stock back, but the order it belonged to stayed PENDING_PAYMENT for ever (in the dev
 * database, orders from two weeks earlier were still "awaiting payment"), so admin lists filled
 * with dead orders and the per-contact open-order cap could never free up.
 *
 * <p>EXPIRED is deliberately not the end of the road: a customer can still pay after the window,
 * because a Paybill payment cannot be recalled. Staff confirming that payment revives the order
 * ({@code PaymentService.confirmSuccess} re-takes the stock if it is still free).
 *
 * <p>Kept separate from {@link OrderExpirySweeper}, which loops over candidates: the sweeper
 * calling this bean is what makes each order its own transaction (a call from inside one class
 * would bypass the transactional proxy).
 */
@Service
public class OrderExpiryService {

    private final CustomerOrderRepository orderRepository;
    private final StockReservationService reservationService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final Clock clock;

    public OrderExpiryService(CustomerOrderRepository orderRepository, StockReservationService reservationService,
                              PaymentRecordRepository paymentRecordRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.clock = clock;
    }

    public List<UUID> unpaidOrderIds() {
        return orderRepository.findIdsByStatus(OrderStatus.PENDING_PAYMENT);
    }

    /**
     * Locks the order, then decides. Locking first matters: {@code PaymentService.confirmSuccess}
     * writes this same row, so whichever commits second must see the other's result rather than
     * a stale PENDING_PAYMENT.
     */
    @Transactional
    public boolean expireIfLapsed(UUID orderId) {
        CustomerOrder order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("No order: " + orderId));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        List<UUID> holds = order.getItems().stream()
                .map(item -> item.getReservation())
                .filter(Objects::nonNull)
                .map(StockReservation::getId)
                .toList();
        if (holds.isEmpty()) {
            // Nothing held: every item was sold while its stock was not tracked yet (V78). Its payment window decides
            // instead, the same one the holds were given and that a "I have paid" claim extends. No window at all is
            // left for staff rather than guessed.
            if (!paymentWindowPassed(orderId)) {
                return false;
            }
        } else if (reservationService.anyActive(holds)) {
            return false;
        }
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
        return true;
    }

    private boolean paymentWindowPassed(UUID orderId) {
        List<Instant> windows = paymentRecordRepository.findByOrderId(orderId).stream()
                .map(PaymentRecord::getExpiresAt)
                .toList();
        Instant now = Instant.now(clock);
        return !windows.isEmpty() && windows.stream().allMatch(w -> w != null && w.isBefore(now));
    }
}
