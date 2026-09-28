package com.nbh.backend.order.service;

import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public OrderExpiryService(CustomerOrderRepository orderRepository, StockReservationService reservationService) {
        this.orderRepository = orderRepository;
        this.reservationService = reservationService;
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
        // No holds at all means nothing to lapse — leave it for staff rather than guess.
        if (holds.isEmpty() || reservationService.anyActive(holds)) {
            return false;
        }
        order.setStatus(OrderStatus.EXPIRED);
        orderRepository.save(order);
        return true;
    }
}
