package com.nbh.backend.order.service;

import com.nbh.backend.order.repository.OrderCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Same atomic-counter approach as StockDocumentNoGenerator (audit finding
 * F10) — see its javadoc for why a random suffix was wrong, and for §4 C6's
 * REQUIRES_NEW reasoning below, which applies identically here:
 * {@code CheckoutService.checkout} calls {@code next()} as its first
 * statement inside a much larger transaction (order creation, every line's
 * stock reservation, VAT/totals, the payment record). Without REQUIRES_NEW,
 * the order_counter row's lock would be held for that whole transaction,
 * serialising every concurrent checkout in the system behind one row —
 * exactly the wrong property during the promotional traffic spikes a
 * retailer's checkout exists to handle.
 */
@Service
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderCounterRepository counterRepository;

    public OrderNoGenerator(OrderCounterRepository counterRepository) {
        this.counterRepository = counterRepository;
    }

    /**
     * REQUIRES_NEW — deliberately, not an oversight; do not revert to
     * REQUIRED without reading StockDocumentNoGenerator.next()'s javadoc
     * first. Same accepted trade-off: a rolled-back checkout burns an order
     * number as a permanent gap rather than reusing it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        String counterKey = "SO-" + LocalDate.now().format(DATE);
        counterRepository.upsertAndIncrement(counterKey);
        long sequence = counterRepository.currentValue(counterKey);
        return counterKey + "-" + String.format("%06d", sequence);
    }
}
