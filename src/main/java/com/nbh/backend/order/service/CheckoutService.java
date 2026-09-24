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
import com.nbh.backend.common.exception.ConflictException;
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
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
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

    /** M-Pesa STK push: the customer answers a phone prompt within seconds. */
    private static final long MPESA_RESERVATION_TTL_MINUTES = 15;
    private static final int REFERENCE_ATTEMPTS = 5;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final BranchRepository branchRepository;
    private final StockLocationRepository locationRepository;
    private final StockBalanceRepository balanceRepository;
    private final CustomerOrderRepository orderRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final StockReservationService reservationService;
    private final OrderNoGenerator orderNoGenerator;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentReferenceGenerator referenceGenerator;
    private final BankPaymentSettings paymentSettings;

    public CheckoutService(CartRepository cartRepository,
                            CartItemRepository cartItemRepository,
                            BranchRepository branchRepository,
                            StockLocationRepository locationRepository,
                            StockBalanceRepository balanceRepository,
                            CustomerOrderRepository orderRepository,
                            PaymentRecordRepository paymentRecordRepository,
                            StockReservationService reservationService,
                            OrderNoGenerator orderNoGenerator,
                            Clock clock,
                            ApplicationEventPublisher eventPublisher,
                            PaymentReferenceGenerator referenceGenerator,
                            BankPaymentSettings paymentSettings) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.branchRepository = branchRepository;
        this.locationRepository = locationRepository;
        this.balanceRepository = balanceRepository;
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
        order.setCustomerAccount(customer);
        order.setCustomerName(request.customerName().trim());
        order.setGuestEmail(request.guestEmail());
        order.setGuestPhone(PhoneNumbers.normalize(request.guestPhone()));
        order.setFulfilmentBranch(fulfilmentBranch);
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
            // The main branch takes the line unless it's short, in which case the first other
            // active branch with enough stock covers it instead (owner's confirmed rule — not a
            // full nearest/cheapest allocator, just a fallback). Reserving here — not at
            // add-to-cart — is the exact "hold starts at checkout" decision. If this throws
            // (insufficient availability everywhere), the whole @Transactional method rolls back,
            // including the order row saved above — nothing is left half-created.
            StockLocation sourceLocation = resolveSourceLocation(
                    cartItem.getVariant(), cartItem.getQty(), salesFloor, fulfilmentBranch);
            StockReservation reservation;
            try {
                reservation = reservationService.reserve(
                        cartItem.getVariant(), sourceLocation, cartItem.getQty(),
                        StockReservation.ReservationSource.ONLINE_CART,
                        "CUSTOMER_ORDER", order.getId(), expiresAt);
            } catch (InsufficientAvailabilityException e) {
                // The customer only ever needs to know an item ran out, never which branch/location
                // came up short — resolveSourceLocation already tried every active branch, so
                // reaching here means it's genuinely unavailable everywhere (see this method's
                // and resolveSourceLocation's own javadoc for why nothing further to try remains).
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

    /**
     * Main-branch-first-with-fallback: the main branch's Sales Floor covers this line if it has
     * enough on hand right now; otherwise the first other active branch (by code, for a
     * deterministic order — not "nearest" or "cheapest") that has enough covers it instead. If
     * nobody has enough, falls back to the main branch anyway so the caller's
     * {@code reservationService.reserve} throws its normal {@code InsufficientAvailabilityException}
     * with its normal message — this is a read-only pre-check, not a hold, so it can't itself
     * guarantee the location it picks still has stock by the time {@code reserve} actually locks
     * it; a lost race there is a genuine (rare) checkout failure, same as today's single-branch flow.
     */
    private StockLocation resolveSourceLocation(ProductVariant variant, BigDecimal qty,
                                                 StockLocation mainSalesFloor, Branch mainBranch) {
        if (availableAt(variant, mainSalesFloor).compareTo(qty) >= 0) {
            return mainSalesFloor;
        }
        for (Branch other : branchRepository.findByIsActiveTrueOrderByCode()) {
            if (other.getId().equals(mainBranch.getId())) continue;
            StockLocation otherFloor = locationRepository
                    .findByBranchIdAndLocationType(other.getId(), LocationType.SALES_FLOOR)
                    .orElse(null);
            if (otherFloor != null && availableAt(variant, otherFloor).compareTo(qty) >= 0) {
                return otherFloor;
            }
        }
        return mainSalesFloor;
    }

    private BigDecimal availableAt(ProductVariant variant, StockLocation location) {
        return balanceRepository.findByVariantIdAndLocationId(variant.getId(), location.getId())
                .map(StockBalance::getAvailableQty)
                .orElse(BigDecimal.ZERO);
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
