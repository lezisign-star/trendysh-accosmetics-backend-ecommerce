package com.nbh.backend.order.service;

import com.nbh.backend.order.dto.AdminOrderDetailDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order as staff read it: its lines, its payments, the codes the customer sent and the price changes staff made while
 * entering it. One place builds it, so the order panel, the screen where staff enter an order and the answer to a return
 * all describe an order the same way.
 */
@Service
public class OrderDetailService {

    private final PaymentRecordRepository payments;
    private final PaymentClaimRepository claims;
    private final OrderPriceChangeService priceChanges;

    public OrderDetailService(PaymentRecordRepository payments, PaymentClaimRepository claims,
                              OrderPriceChangeService priceChanges) {
        this.payments = payments;
        this.claims = claims;
        this.priceChanges = priceChanges;
    }

    @Transactional(readOnly = true)
    public AdminOrderDetailDto detail(CustomerOrder order) {
        return AdminOrderDetailDto.from(order, payments.findByOrderId(order.getId()),
                        claims.findByOrderIdNewestFirst(order.getId()))
                .withPriceChanges(priceChanges.forOrder(order.getId()));
    }
}
