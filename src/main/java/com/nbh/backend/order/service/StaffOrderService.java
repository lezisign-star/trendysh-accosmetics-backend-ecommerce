package com.nbh.backend.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbh.backend.cart.service.CartService;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.dto.AdminOrderDetailDto;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.dto.StaffOrderDtos.PayNow;
import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderRequest;
import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderResponse;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.PaymentService;
import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orders that staff enter for a customer (owner, 2026-10-19: "staff can place a kit order for a customer"), for the
 * customer who phones, messages on WhatsApp or stands at the counter rather than using the site.
 *
 * <p>There is deliberately no second way of making an order. The staff member builds a basket with the shop's ordinary
 * basket endpoints under a session of their own, so every line is priced (deals, wholesale, a kit and its picks), kit-picked
 * and stock-checked exactly as it is for a shopper, and this service then runs the same {@link CheckoutService} over that
 * basket: the same stock holds, the same order, the same payment record and the same emails. What differs is only who is
 * placing it ({@link CheckoutService.Placement}): no limit on orders waiting for payment, the order remembers who entered
 * it, and it does not ring the new-order alert for them.
 *
 * <p>The customer then pays with the link (the order is saved and its stock held, as at the checkout), or staff take the
 * payment at once ({@code payNow}: cash, M-PESA or bank), which confirms it in the same transaction so the order is paid
 * the moment it exists, or does not exist at all if the code turns out to belong to another order.
 */
@Service
public class StaffOrderService {

    private final CartService cartService;
    private final CheckoutService checkoutService;
    private final PaymentService paymentService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentClaimRepository claimRepository;
    private final ObjectMapper objectMapper;

    public StaffOrderService(CartService cartService, CheckoutService checkoutService, PaymentService paymentService,
                             PaymentRecordRepository paymentRecordRepository, PaymentClaimRepository claimRepository,
                             ObjectMapper objectMapper) {
        this.cartService = cartService;
        this.checkoutService = checkoutService;
        this.paymentService = paymentService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.claimRepository = claimRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StaffOrderResponse place(StaffOrderRequest request, StaffUser staff) {
        requireWhereItGoes(request);
        PayNow payNow = request.payNow();
        if (payNow != null) {
            requireUsablePayment(payNow);
        }
        if (cartService.basketValue(request.cartSession()).signum() <= 0) {
            throw new UnprocessableException("Add at least one product to the order.");
        }

        // The same first step as the checkout: bring every line to today's price, make sure everything can still be bought,
        // and refuse (409 PRICE_CHANGED) when the total the screen showed is no longer the real one.
        cartService.refreshPricesForCheckout(request.cartSession(), request.expectedSubtotal());

        CheckoutRequest checkout = new CheckoutRequest(
                request.customerName().trim(), blankToNull(request.email()), request.phone(), request.fulfilmentType(),
                blankToNull(request.deliveryAddress()), request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER
                        ? request.deliveryMethod() : null,
                null, null, request.expectedSubtotal(), null, blankToNull(request.deliveryContactPhone()),
                request.deliveryDetails());
        CheckoutService.Placement placement = new CheckoutService.Placement(
                cut(staff.getFullName(), 200), cut(staff.getEmail(), 120));
        CheckoutService.CheckoutResult result = checkoutService.checkout(request.cartSession(), checkout, null, placement);

        PaymentRecord payment = result.paymentRecord();
        if (payNow != null) {
            payment = paymentService.confirmSuccess(payment.getId(), blankToNull(payNow.reference()),
                    paymentNote(staff, payNow), payNow.method());
        }
        CustomerOrder order = result.order();
        AdminOrderDetailDto detail = AdminOrderDetailDto.from(order, paymentRecordRepository.findByOrderId(order.getId()),
                claimRepository.findByOrderIdNewestFirst(order.getId()));
        boolean paid = payment.getStatus() == PaymentRecord.PaymentStatus.SUCCESS;
        return new StaffOrderResponse(detail, payment.getId(), paid ? null : payment.getPaymentReference());
    }

    /** A pickup names the shop, a delivery names how it travels: staff are asked for both, where the shopper's form has them as steps. */
    private static void requireWhereItGoes(StaffOrderRequest request) {
        if (request.fulfilmentType() == FulfilmentType.PICKUP && blankToNull(request.deliveryAddress()) == null) {
            throw new UnprocessableException("Choose the shop the customer will collect from.");
        }
        if (request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER) {
            if (request.deliveryMethod() == null) {
                throw new UnprocessableException("Choose how the delivery travels: rider, matatu or courier.");
            }
            if (blankToNull(request.deliveryAddress()) == null) {
                throw new UnprocessableException("Tell us where to deliver the order.");
            }
        }
    }

    /** Cash, M-PESA or bank; a code or reference is needed for the last two (a cash sale may have only a receipt, or nothing). */
    private static void requireUsablePayment(PayNow payNow) {
        if (payNow.method() == PaymentRecord.PaymentMethod.CARD) {
            throw new UnprocessableException("Choose cash, M-PESA or bank transfer.");
        }
        if (payNow.method() != PaymentRecord.PaymentMethod.CASH && blankToNull(payNow.reference()) == null) {
            throw new UnprocessableException(payNow.method() == PaymentRecord.PaymentMethod.MPESA
                    ? "Enter the M-PESA code the customer paid with."
                    : "Enter the bank reference the customer paid with.");
        }
    }

    /** The payment record's note (a json column): that staff took this payment while entering the order, and who. */
    private String paymentNote(StaffUser staff, PayNow payNow) {
        Map<String, String> note = new LinkedHashMap<>();
        note.put("type", "staff_entered_order");
        note.put("by", staff.getFullName());
        if (blankToNull(payNow.notes()) != null) {
            note.put("notes", payNow.notes().trim());
        }
        try {
            return objectMapper.writeValueAsString(note);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise the payment note", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
