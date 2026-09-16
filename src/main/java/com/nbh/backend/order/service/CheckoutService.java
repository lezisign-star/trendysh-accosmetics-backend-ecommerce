package com.nbh.backend.order.service;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.entity.LocationType;
import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.stock.entity.StockReservation;
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
 * Selling prices are treated as VAT-inclusive (the Kenyan retail norm — the
 * shelf/display price already includes VAT), so vatTotal here is the VAT
 * COMPONENT of the subtotal, extracted for reporting, not an amount added on
 * top; grandTotal = subtotal + deliveryFee.
 */
@Service
public class CheckoutService {

    private static final long RESERVATION_TTL_MINUTES = 15;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final BranchRepository branchRepository;
    private final StockLocationRepository locationRepository;
    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final StockReservationService reservationService;
    private final OrderNoGenerator orderNoGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    public CheckoutService(CartRepository cartRepository,
                            CartItemRepository cartItemRepository,
                            BranchRepository branchRepository,
                            StockLocationRepository locationRepository,
                            CustomerOrderRepository orderRepository,
                            PaymentRecordRepository paymentRecordRepository,
                            StockReservationService reservationService,
                            OrderNoGenerator orderNoGenerator,
                            Clock clock,
                            ApplicationEventPublisher eventPublisher) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.branchRepository = branchRepository;
        this.locationRepository = locationRepository;
        this.orderRepository = orderRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.reservationService = reservationService;
        this.orderNoGenerator = orderNoGenerator;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public CheckoutResult checkout(String sessionId, CheckoutRequest request) {
        Cart cart = cartRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("No cart for this session"));
        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        if (cartItems.isEmpty()) {
            throw new IllegalStateException("Cannot check out an empty cart");
        }

        // §4 C7: this is a deployment/seed-data problem, not something the
        // customer's request got wrong — 500, not 422 (a customer seeing
        // "422 — no branch configured" can't do anything with that, and
        // GlobalExceptionHandler previously logged nothing, so it wouldn't
        // even page anyone).
        Branch fulfilmentBranch = branchRepository.findByIsDefaultOnlineFulfilmentTrue()
                .orElseThrow(() -> new ConfigurationException("No branch is configured as the default online-fulfilment branch"));
        StockLocation salesFloor = locationRepository
                .findByBranchIdAndLocationType(fulfilmentBranch.getId(), LocationType.SALES_FLOOR)
                .orElseThrow();

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNoGenerator.next());
        order.setGuestEmail(request.guestEmail());
        order.setGuestPhone(request.guestPhone());
        order.setFulfilmentBranch(fulfilmentBranch);
        order.setFulfilmentType(request.fulfilmentType());
        order.setDeliveryAddress(request.deliveryAddress());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(BigDecimal.ZERO);
        order.setVatTotal(BigDecimal.ZERO);
        order.setDeliveryFee(BigDecimal.ZERO); // delivery fee calculation is not built yet — flagged in HANDOFF
        order.setGrandTotal(BigDecimal.ZERO);
        order = orderRepository.save(order); // needs an id before reservations can reference it

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vatTotal = BigDecimal.ZERO;
        Instant expiresAt = Instant.now(clock).plusSeconds(RESERVATION_TTL_MINUTES * 60);

        for (CartItem cartItem : cartItems) {
            // Reserving here — not at add-to-cart — is the exact "hold starts at
            // checkout" decision. If this throws (insufficient availability),
            // the whole @Transactional method rolls back, including the order
            // row saved above — nothing is left half-created.
            StockReservation reservation = reservationService.reserve(
                    cartItem.getVariant(), salesFloor, cartItem.getQty(),
                    StockReservation.ReservationSource.ONLINE_CART,
                    "CUSTOMER_ORDER", order.getId(), expiresAt);

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
        paymentRecord.setProvider("DARAJA");
        paymentRecord.setMethod(PaymentRecord.PaymentMethod.MPESA);
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

    public record CheckoutResult(CustomerOrder order, PaymentRecord paymentRecord) {}
}
