package com.nbh.backend.order.service;

import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.TooManyRequestsException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Turns a cart into a PENDING_PAYMENT order, reserving stock for every line
 * in the SAME transaction the order is created in — if any line can't be
 * reserved (insufficient availability), the whole checkout rolls back
 * atomically, including the order itself. This is the point in the system
 * where the founder's confirmed 15-minute checkout-time hold actually starts
 * (architecture doc §4.5's lifecycle table: "Begin checkout / M-Pesa STK
 * sent -> ACTIVE, ONLINE_CART, expires_at = now()+15min").
 *
 * <p>Branches were removed 2026-09-24 (founder decision — see HANDOFF.md).
 * This service used to try the main branch first and fall back to another
 * active branch if it was short, then reserve there — a real defect, since
 * {@code OrderFulfilmentService.fulfil} always dispatched from the order's
 * nominal fulfilment branch regardless of which branch the reservation
 * actually landed on. There is now exactly one place to reserve from (SHOP),
 * so that class of bug is gone, not just fixed for today's data.
 *
 * Selling prices are treated as VAT-inclusive (the Kenyan retail norm — the
 * shelf/display price already includes VAT), so vatTotal here is the VAT
 * COMPONENT of the subtotal, extracted for reporting, not an amount added on
 * top; grandTotal = subtotal + deliveryFee.
 */
@Service
public class CheckoutService {

    /** M-Pesa STK push: the customer answers a phone prompt within seconds. */
    private static final long MPESA_RESERVATION_TTL_MINUTES = 15;
    private static final int REFERENCE_ATTEMPTS = 5;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final StockReservationService reservationService;
    private final OrderNoGenerator orderNoGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentReferenceGenerator referenceGenerator;
    private final BankPaymentSettings paymentSettings;
    private final int maxOpenOrdersPerContact;

    public CheckoutService(CartRepository cartRepository,
                            CartItemRepository cartItemRepository,
                            CustomerOrderRepository orderRepository,
                            PaymentRecordRepository paymentRecordRepository,
                            StockReservationService reservationService,
                            OrderNoGenerator orderNoGenerator,
                            Clock clock,
                            ApplicationEventPublisher eventPublisher,
                            PaymentReferenceGenerator referenceGenerator,
                            BankPaymentSettings paymentSettings,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${app.checkout.max-open-orders-per-contact:3}") int maxOpenOrdersPerContact) {
        this.maxOpenOrdersPerContact = maxOpenOrdersPerContact;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.reservationService = reservationService;
        this.orderNoGenerator = orderNoGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.referenceGenerator = referenceGenerator;
        this.paymentSettings = paymentSettings;
    }

    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request) {
        return checkout(sessionId, request, null);
    }

    /** {@code customer} is the signed-in shopper, or null for a guest checkout. */
    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request,
                                    com.nbh.backend.customer.entity.CustomerAccount customer) {
        Cart cart = cartRepository.findWithLockBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("No cart for this session"));
        if (cart.getStatus() != Cart.CartStatus.ACTIVE) {
            throw new ConflictException("This basket has already been checked out");
        }
        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Cannot check out an empty cart");
        }
        // Every checkout holds stock for the whole payment window, and the public endpoint has no
        // login, so without a limit one person could tie up the shelf with orders they never pay
        // for. Counting by phone OR email means changing just one of them doesn't get round it;
        // OrderExpiryService is what frees a slot again once a hold lapses.
        String contactPhone = PhoneNumbers.normalize(request.guestPhone());
        long open = orderRepository.countByStatusForContact(
                OrderStatus.PENDING_PAYMENT, contactPhone, request.guestEmail());
        if (open >= maxOpenOrdersPerContact) {
            throw new TooManyRequestsException("You already have " + open
                    + " orders waiting for payment. Please pay for one of them, or wait for it to expire,"
                    + " before placing another.");
        }

        // Phase 0 fix (2026-09-27), D1: reserve in a deterministic order (variant id ASC) —
        // the same order StockPostingService.post already locks its balance rows in. Without
        // this, two concurrent checkouts (or a checkout racing a dispatch) that touch the same
        // two SKUs in opposite orders can deadlock in Postgres, which surfaces to a customer as
        // a raw 500 instead of either request simply waiting its turn.
        cartItems = cartItems.stream()
                .sorted(java.util.Comparator.comparing(ci -> ci.getVariant().getId()))
                .toList();

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNoGenerator.next());
        order.setCustomerAccount(customer);
        order.setCustomerName(request.customerName().trim());
        order.setGuestEmail(request.guestEmail());
        order.setGuestPhone(contactPhone);
        order.setFulfilmentType(request.fulfilmentType());
        order.setDeliveryAddress(request.deliveryAddress());
        // Pickup has no courier leg, so a stray method on a pickup order is dropped, not stored.
        order.setDeliveryMethod(request.fulfilmentType() == FulfilmentType.DELIVERY_COURIER ? request.deliveryMethod() : null);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(BigDecimal.ZERO);
        order.setVatTotal(BigDecimal.ZERO);
        order.setDeliveryFee(BigDecimal.ZERO); // delivery fee calculation is not built yet — flagged in HANDOFF
        order.setGrandTotal(BigDecimal.ZERO);
        order = orderRepository.save(order); // needs an id before reservations can reference it

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        // The stock hold and the payment window are the same instant, always. A bank
        // transfer takes far longer than answering an STK prompt, so its window is longer.
        Instant expiresAt = Instant.now(clock).plus(paymentSettings.bankMode()
                ? paymentSettings.paymentWindow()
                : java.time.Duration.ofMinutes(MPESA_RESERVATION_TTL_MINUTES));

        for (CartItem cartItem : cartItems) {
            // Reserving here — not at add-to-cart — is the exact "hold starts at
            // checkout" decision. If this throws (insufficient availability), the
            // whole @Transactional method rolls back, including the order row
            // saved above — nothing is left half-created.
            StockReservation reservation;
            try {
                reservation = reservationService.reserve(
                        cartItem.getVariant(), cartItem.getQty(),
                        StockReservation.ReservationSource.ONLINE_CART,
                        "CUSTOMER_ORDER", order.getId(), expiresAt);
            } catch (InsufficientAvailabilityException e) {
                throw new InsufficientAvailabilityException(
                        cartItem.getVariant().getProduct().getName()
                                + (cartItem.getVariant().getVariantName().equalsIgnoreCase("Default")
                                        ? "" : " (" + cartItem.getVariant().getVariantName() + ")")
                                + " just went out of stock — please update your basket");
            }

            CustomerOrderItem orderItem = new CustomerOrderItem();
            orderItem.setOrder(order);
            orderItem.setVariant(cartItem.getVariant());
            orderItem.setQty(cartItem.getQty());
            orderItem.setUnitPrice(cartItem.getUnitPriceSnapshot());
            orderItem.setVatRate(cartItem.getVariant().getVatRate());
            orderItem.setLineTotal(cartItem.getLineTotal());
            orderItem.setReservation(reservation);
            order.getItems().add(orderItem);

            subtotal = subtotal.add(cartItem.getLineTotal());
            if (!cartItem.getVariant().isVatExempt()) {
                BigDecimal vatRate = cartItem.getVariant().getVatRate();
                BigDecimal vatComponent = cartItem.getLineTotal()
                        .multiply(vatRate)
                        .divide(BigDecimal.ONE.add(vatRate), 2, RoundingMode.HALF_UP);
                vatTotal = vatTotal.add(vatComponent);
            }
        }

        order.setSubtotal(subtotal);
        order.setVatTotal(vatTotal);
        order.setGrandTotal(subtotal.add(order.getDeliveryFee()));
        order = orderRepository.save(order);

        cart.setStatus(Cart.CartStatus.CONVERTED);
        cartRepository.save(cart);

        PaymentRecord paymentRecord = new PaymentRecord();
        paymentRecord.setOrder(order);
        if (paymentSettings.bankMode()) {
            paymentRecord.setProvider("BANK");
            paymentRecord.setMethod(PaymentRecord.PaymentMethod.BANK);
            paymentRecord.setPaymentReference(uniqueReference());
            paymentRecord.setExpiresAt(expiresAt);
        } else {
            paymentRecord.setProvider("DARAJA");
            paymentRecord.setMethod(PaymentRecord.PaymentMethod.MPESA);
        }
        paymentRecord.setAmount(order.getGrandTotal());
        paymentRecord.setStatus(PaymentRecord.PaymentStatus.INITIATED);
        paymentRecord = paymentRecordRepository.save(paymentRecord);

        // §2: published from inside this @Transactional method, delivered
        // AFTER_COMMIT — see EventLoggingListener's javadoc for why (this exact
        // method can still roll back after this point if a later line's
        // reservation fails, which would already have happened above had it
        // failed; this call only runs once every line succeeded).
        eventPublisher.publishEvent(new OrderPlacedEvent(
                order.getId(), order.getOrderNo(), order.getGuestEmail(), order.getGuestPhone(),
                order.getGrandTotal(), cartItems.size()));

        return new CheckoutResult(order, paymentRecord);
    }

    /** 30^6 combinations makes a clash rare; the unique index is the real guard, this just avoids a 500 on the odd hit. */
    private String uniqueReference() {
        for (int attempt = 0; attempt < REFERENCE_ATTEMPTS; attempt++) {
            String candidate = referenceGenerator.generate();
            if (paymentRecordRepository.findByPaymentReference(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique payment reference");
    }

    public record CheckoutResult(CustomerOrder order, PaymentRecord paymentRecord) {}
}
