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
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.service.StockReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * §3.4-B: {@code CheckoutService.checkout}'s VAT/totals maths, mocked. This
 * class had ZERO test coverage of any kind before this — money maths with no
 * test, per the plan's framing. The rollback-on-partial-reservation-failure
 * property itself (the actual transactional guarantee) is NOT re-proven
 * here — that needs a real transaction and belongs to an integration test;
 * this only pins that the exception propagates unchanged out of checkout().
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceUnitTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private StockLocationRepository locationRepository;
    @Mock private StockBalanceRepository balanceRepository;
    @Mock private CustomerOrderRepository orderRepository;
    @Mock private PaymentRecordRepository paymentRecordRepository;
    @Mock private StockReservationService reservationService;
    @Mock private OrderNoGenerator orderNoGenerator;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private CheckoutService checkoutService;

    private static final String SESSION_ID = "session-under-test";
    private Cart cart;
    private Branch fulfilmentBranch;
    private StockLocation salesFloor;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(cartRepository, cartItemRepository, branchRepository,
                locationRepository, balanceRepository, orderRepository, paymentRecordRepository, reservationService,
                orderNoGenerator, clock, eventPublisher, new PaymentReferenceGenerator(), bankSettings(BANK));

        cart = new Cart();
        cart.setId(UUID.randomUUID());
        cart.setSessionId(SESSION_ID);
        cart.setStatus(Cart.CartStatus.ACTIVE);
        lenient().when(cartRepository.findWithLockBySessionId(SESSION_ID)).thenReturn(Optional.of(cart));

        fulfilmentBranch = new Branch();
        fulfilmentBranch.setId(UUID.randomUUID());
        fulfilmentBranch.setCode("BR-A");
        fulfilmentBranch.setDefaultOnlineFulfilment(true);
        lenient().when(branchRepository.findByIsDefaultOnlineFulfilmentTrue()).thenReturn(Optional.of(fulfilmentBranch));

        salesFloor = new StockLocation();
        salesFloor.setId(UUID.randomUUID());
        salesFloor.setCode("BR-A/SALES_FLOOR");
        lenient().when(locationRepository.findByBranchIdAndLocationType(fulfilmentBranch.getId(), LocationType.SALES_FLOOR))
                .thenReturn(Optional.of(salesFloor));

        lenient().when(orderNoGenerator.next()).thenReturn("SO-TEST-000001");
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> {
            var order = inv.getArgument(0, com.nbh.backend.order.entity.CustomerOrder.class);
            if (order.getId() == null) {
                order.setId(UUID.randomUUID());
            }
            return order;
        });
        lenient().when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reservationService.reserve(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new StockReservation());
    }

    private static final BankPaymentSettings.CollectionMode BANK = BankPaymentSettings.CollectionMode.BANK;

    private static BankPaymentSettings bankSettings(BankPaymentSettings.CollectionMode mode) {
        return new BankPaymentSettings(mode, 45, "Co-op Bank", "400200", "<ref>",
                "Trendysh Accosmetic", "01100123456789", "Nairobi", "");
    }

    @Test
    void bankCheckoutGivesTheOrderAValidReferenceAndAPaymentWindowMatchingTheStockHold() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var payment = checkoutService.checkout(SESSION_ID, request()).paymentRecord();

        assertThat(payment.getProvider()).isEqualTo("BANK");
        assertThat(payment.getMethod()).isEqualTo(com.nbh.backend.payment.entity.PaymentRecord.PaymentMethod.BANK);
        assertThat(new PaymentReferenceGenerator().normaliseAndValidate(payment.getPaymentReference()))
                .isEqualTo(payment.getPaymentReference());
        assertThat(payment.getExpiresAt()).isEqualTo(Instant.parse("2026-01-01T00:45:00Z"));
        verify(reservationService).reserve(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-01-01T00:45:00Z")));
    }

    @Test
    void mpesaModeKeepsTheDarajaRecordAndTheShortFifteenMinuteHold() {
        checkoutService = new CheckoutService(cartRepository, cartItemRepository, branchRepository,
                locationRepository, balanceRepository, orderRepository, paymentRecordRepository, reservationService,
                orderNoGenerator, clock, eventPublisher, new PaymentReferenceGenerator(),
                bankSettings(BankPaymentSettings.CollectionMode.MPESA));
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var payment = checkoutService.checkout(SESSION_ID, request()).paymentRecord();

        assertThat(payment.getProvider()).isEqualTo("DARAJA");
        assertThat(payment.getPaymentReference()).isNull();
        verify(reservationService).reserve(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-01-01T00:15:00Z")));
    }

    private CartItem cartItem(BigDecimal unitPrice, BigDecimal qty, BigDecimal vatRate, boolean vatExempt) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("Test product");

        ProductVariant variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 8));
        variant.setVariantName("Test variant");
        variant.setSellingPrice(unitPrice);
        variant.setVatRate(vatRate);
        variant.setVatExempt(vatExempt);

        CartItem item = new CartItem();
        item.setCart(cart);
        item.setVariant(variant);
        item.setQty(qty);
        item.setUnitPriceSnapshot(unitPrice);
        item.setProductNameSnapshot("Test product");
        item.setVariantNameSnapshot("Test variant");
        return item;
    }

    private CheckoutRequest request() {
        return new CheckoutRequest("Test Customer", "customer@example.com", "254700000000", FulfilmentType.BRANCH_PICKUP, null, null);
    }

    @Test
    void customerNameIsTrimmedAndStoredAndDeliveryMethodKeptForCourierOrders() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var request = new CheckoutRequest("  Njeri Nails Studio  ", "n@example.com", "0720 000 111",
                FulfilmentType.DELIVERY_COURIER, "Riverside Court, Kilimani, Nairobi", "BODA");
        var order = checkoutService.checkout(SESSION_ID, request).order();

        assertThat(order.getCustomerName()).isEqualTo("Njeri Nails Studio");
        assertThat(order.getGuestPhone()).isEqualTo("254720000111");
        assertThat(order.getDeliveryMethod()).isEqualTo("BODA");
    }

    @Test
    void checkingOutAnAlreadyConvertedBasketIsRefusedNotDuplicated() {
        cart.setStatus(Cart.CartStatus.CONVERTED);

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(com.nbh.backend.common.exception.ConflictException.class);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void deliveryMethodIsDroppedForPickupOrders() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var request = new CheckoutRequest("Test Customer", "n@example.com", "254700000000",
                FulfilmentType.BRANCH_PICKUP, null, "VEHICLE");

        assertThat(checkoutService.checkout(SESSION_ID, request).order().getDeliveryMethod()).isNull();
    }

    @Test
    void vatIsExtractedFromAVatInclusivePriceNotAddedOnTop() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("1160.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var result = checkoutService.checkout(SESSION_ID, request());

        assertThat(result.order().getVatTotal()).isEqualByComparingTo("160.00");
        assertThat(result.order().getSubtotal()).isEqualByComparingTo("1160.00");
        assertThat(result.order().getGrandTotal()).isEqualByComparingTo("1160.00");
    }

    @Test
    void vatExemptLineContributesZeroVatButFullSubtotal() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("1000.00"), BigDecimal.ONE, new BigDecimal("0.1600"), true)));

        var result = checkoutService.checkout(SESSION_ID, request());

        assertThat(result.order().getVatTotal()).isEqualByComparingTo("0.00");
        assertThat(result.order().getSubtotal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void mixedCartSumsExemptAndStandardLinesCorrectly() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                cartItem(new BigDecimal("1000.00"), BigDecimal.ONE, new BigDecimal("0.1600"), true),
                cartItem(new BigDecimal("1160.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var result = checkoutService.checkout(SESSION_ID, request());

        assertThat(result.order().getSubtotal()).isEqualByComparingTo("2160.00");
        assertThat(result.order().getVatTotal()).isEqualByComparingTo("160.00");
    }

    @Test
    void perLineRoundingIsAppliedPerLineNotOnTheSummedTotal() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                cartItem(new BigDecimal("333.33"), BigDecimal.ONE, new BigDecimal("0.1600"), false),
                cartItem(new BigDecimal("333.33"), BigDecimal.ONE, new BigDecimal("0.1600"), false),
                cartItem(new BigDecimal("333.33"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var result = checkoutService.checkout(SESSION_ID, request());

        // Each line: 333.33 * 0.16 / 1.16 = 45.9765... -> HALF_UP to 45.98.
        // Summed per-line: 45.98 * 3 = 137.94, NOT round(999.99 * 0.16/1.16) computed on the total.
        assertThat(result.order().getVatTotal()).isEqualByComparingTo("137.94");
        assertThat(result.order().getSubtotal()).isEqualByComparingTo("999.99");
    }

    @Test
    void emptyCartThrowsIllegalStateException() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot check out an empty cart");
    }

    @Test
    void noDefaultFulfilmentBranchConfiguredThrows() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(branchRepository.findByIsDefaultOnlineFulfilmentTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("default online-fulfilment branch");
    }

    @Test
    void aLineThatCannotBeReservedAbortsTheWholeCheckoutByPropagatingACustomerFriendlyException() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false),
                cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        when(reservationService.reserve(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new StockReservation())
                .thenThrow(new InsufficientAvailabilityException(
                        "Requested 1 of SKU-1234 at BR-A/SALES_FLOOR but only 0 is available"));

        // The whole point of CheckoutService's catch block: the raw, location-code-bearing
        // message from StockReservationService never reaches whoever sees this exception —
        // a customer only needs to know an item ran out, not which internal branch/location.
        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(InsufficientAvailabilityException.class)
                .hasMessageContaining("Test product")
                .hasMessageContaining("out of stock")
                .hasMessageNotContaining("SALES_FLOOR")
                .hasMessageNotContaining("BR-A");
    }

    @Test
    void deliveryFeeIsZeroAndGrandTotalEqualsSubtotal() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("750.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var result = checkoutService.checkout(SESSION_ID, request());

        assertThat(result.order().getDeliveryFee()).isEqualByComparingTo("0");
        assertThat(result.order().getGrandTotal()).isEqualByComparingTo(result.order().getSubtotal());
    }
}
