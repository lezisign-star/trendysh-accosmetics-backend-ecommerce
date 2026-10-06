package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.ProductSaleEvent;
import com.nbh.backend.catalog.repository.ProductSaleEventRepository;
import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.PaymentConfirmedEvent;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;

/**
 * Its own class in the catalog package, per {@code EventLoggingListener}'s
 * own javadoc ("when a real consumer exists... that listener belongs in its
 * own class in the relevant domain package"). Writes one {@link ProductSaleEvent}
 * per order line when payment is CONFIRMED — not when the order is merely
 * placed — since an abandoned/unpaid order must never count as a sale.
 *
 * {@code CustomerOrderRepository.findById} already {@code @EntityGraph}-fetches
 * {@code items}/{@code items.variant} (session 3's C5 fix), so no lazy access
 * happens here despite this running AFTER_COMMIT on a fresh session.
 *
 * {@code @Transactional(propagation = REQUIRES_NEW)} is not optional here —
 * Spring refuses to even start up otherwise: an AFTER_COMMIT
 * {@code @TransactionalEventListener} runs after the original transaction is
 * already gone, so a plain {@code @Transactional} (which would try to join
 * one) is rejected at bean-initialisation time with "must not be annotated
 * with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED"
 * — found immediately by the full test suite's context load, not guessed
 * from documentation.
 *
 * Failures here are caught and logged rather than rethrown: losing an
 * analytics row must never turn an already-successful payment confirmation
 * into a 500 for the caller.
 */
@Slf4j
@Component
public class ProductAnalyticsListener {

    private final CustomerOrderRepository orderRepository;
    private final ProductSaleEventRepository saleEventRepository;
    private final Clock clock;

    public ProductAnalyticsListener(CustomerOrderRepository orderRepository,
                                     ProductSaleEventRepository saleEventRepository,
                                     Clock clock) {
        this.orderRepository = orderRepository;
        this.saleEventRepository = saleEventRepository;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        try {
            CustomerOrder order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new EntityNotFoundException("No order: " + event.orderId()));
            Instant now = Instant.now(clock);
            for (CustomerOrderItem item : order.getItems()) {
                ProductSaleEvent sale = new ProductSaleEvent();
                sale.setProduct(item.getVariant().getProduct());
                sale.setVariant(item.getVariant());
                sale.setOrder(order);
                sale.setQty(item.getQty());
                sale.setOccurredAt(now);
                saleEventRepository.save(sale);
            }
        } catch (Exception e) {
            log.error("Failed to record product sale events for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }

    /**
     * Staff can cancel an order after it was paid (the customer changed their mind, the goods were damaged): the
     * units were counted as sold when the payment landed, so they are taken back out. Found in the 2026-10-06
     * go-live QA, where "top sellers" showed 7 lipsticks sold against 5 on orders that were still standing.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderCancelled(OrderCancelledEvent event) {
        try {
            int removed = saleEventRepository.deleteByOrderId(event.orderId());
            if (removed > 0) {
                log.info("Order {} was cancelled: removed its {} product sale event(s)", event.orderNo(), removed);
            }
        } catch (Exception e) {
            log.error("Failed to remove product sale events for cancelled order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }
}
