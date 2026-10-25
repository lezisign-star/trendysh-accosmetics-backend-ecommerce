package com.nbh.backend.order.service;

import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.payment.service.PaymentReferenceGenerator;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
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
 *
 * <p>Branches were removed 2026-09-24 (founder decision — see HANDOFF.md):
 * there is no branch/location setup here any more, and reservation always
 * targets SHOP implicitly.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceUnitTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private CustomerOrderRepository orderRepository;
    @Mock private PaymentRecordRepository paymentRecordRepository;
    @Mock private StockReservationService reservationService;
    @Mock private OrderNoGenerator orderNoGenerator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private com.nbh.backend.tumaboda.TumaBodaQuoteService quoteService;
    @Mock private com.nbh.backend.packages.service.PackageService packageService;
    @Mock private VariantCovers variantCovers;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private CheckoutService checkoutService;

    private static final String SESSION_ID = "session-under-test";
    private Cart cart;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(cartRepository, cartItemRepository,
                orderRepository, paymentRecordRepository, reservationService,
                orderNoGenerator, clock, eventPublisher, new PaymentReferenceGenerator(), bankSettings(BANK),
                MAX_OPEN_ORDERS, quoteService, packageService, variantCovers);

        cart = new Cart();
        cart.setId(UUID.randomUUID());
        cart.setSessionId(SESSION_ID);
        cart.setStatus(Cart.CartStatus.ACTIVE);
        lenient().when(cartRepository.findWithLockBySessionId(SESSION_ID)).thenReturn(Optional.of(cart));

        lenient().when(orderNoGenerator.next()).thenReturn("SO-TEST-000001");
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> {
            var order = inv.getArgument(0, com.nbh.backend.order.entity.CustomerOrder.class);
            if (order.getId() == null) {
                order.setId(UUID.randomUUID());
            }
            return order;
        });
        lenient().when(paymentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reservationService.reserve(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new StockReservation());
        // A line that is not a package expands into nothing, as the real service answers; the package tests below say otherwise.
        lenient().when(packageService.selection(any(), any()))
                .thenReturn(new com.nbh.backend.packages.service.PackageService.Selection(List.of(), false));
    }

    private static final BankPaymentSettings.CollectionMode BANK = BankPaymentSettings.CollectionMode.BANK;
    private static final int MAX_OPEN_ORDERS = 3;

    @Test
    void aContactAlreadyAtTheOpenOrderLimitCannotHoldMoreStock() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(orderRepository.countByStatusForContact(any(), any(), any())).thenReturn((long) MAX_OPEN_ORDERS);

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(com.nbh.backend.common.exception.TooManyRequestsException.class)
                .hasMessageContaining("3 orders waiting for payment");
        verify(reservationService, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void oneOrderUnderTheLimitStillChecksOut() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(orderRepository.countByStatusForContact(any(), any(), any())).thenReturn((long) MAX_OPEN_ORDERS - 1);

        assertThat(checkoutService.checkout(SESSION_ID, request()).order().getOrderNo()).isEqualTo("SO-TEST-000001");
    }

    @Test
    void aBasketHoldingADemoProductIsRefusedBeforeAnyOrderOrStockHoldExists() {
        // The controller refuses demo products while pricing the basket; this is the same rule where the order is
        // created, so no caller of checkout() can turn a demo product into a real order and a real payment.
        CartItem real = cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false);
        CartItem demo = cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false);
        demo.getVariant().getProduct().setName("Chrome Gel Polish");
        demo.getVariant().getProduct().setMockData(true);
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(real, demo));

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(com.nbh.backend.common.exception.UnprocessableException.class)
                .hasMessageContaining("Chrome Gel Polish is a demo product and can't be ordered");
        verify(orderRepository, never()).save(any());
        verify(reservationService, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(paymentRecordRepository, never()).save(any());
    }

    @Test
    void aRealPackageMadeOfDemoComponentsIsRefusedBeforeAnythingIsReserved() {
        // The package line's own product is real, so the line-level guard passes; the demo rule must also hold for the
        // components the package expands into (a real package can be assembled from demo variants while demo mode is on).
        CartItem packageLine = cartItem(new BigDecimal("900.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false);
        packageLine.getVariant().getProduct().setName("Nail Tech Starter Package");
        ProductVariant demoComponent = cartItem(new BigDecimal("300.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false).getVariant();
        demoComponent.getProduct().setMockData(true);
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(packageLine));
        when(packageService.selection(eq(packageLine.getVariant().getId()), any()))
                .thenReturn(new com.nbh.backend.packages.service.PackageService.Selection(
                        List.of(part(demoComponent, BigDecimal.ONE)), false));

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(com.nbh.backend.common.exception.UnprocessableException.class)
                .hasMessageContaining("Nail Tech Starter Package is a demo product and can't be ordered");
        verify(reservationService, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(paymentRecordRepository, never()).save(any());
    }

    @Test
    void everyLineFreezesWhatWasBoughtAndLaterEditsToTheVariantNeverReachIt() {
        CartItem line = cartItem(new BigDecimal("650.00"), new BigDecimal("2"), new BigDecimal("0.1600"), false);
        ProductVariant variant = line.getVariant();
        variant.getProduct().setName("Velvet Matte Lipstick");
        variant.setSku("VML-RR-50");
        variant.setVariantName("Velvet Matte Lipstick – Ruby Red / 50ml");
        variant.setOptionLabel("Ruby Red / 50ml");
        variant.setOptionColor("Ruby Red");
        variant.setOptionSize("50ml");
        variant.setSwatchHex("#9B111E");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(line));
        when(variantCovers.urlsFor(any()))
                .thenReturn(java.util.Map.of(variant.getId(), "https://res.cloudinary.com/demo/image/upload/ruby.jpg"));

        CustomerOrderItem item = checkoutService.checkout(SESSION_ID, request()).order().getItems().get(0);

        assertThat(item.getProductNameSnapshot()).isEqualTo("Velvet Matte Lipstick");
        assertThat(item.getVariantNameSnapshot()).isEqualTo("Velvet Matte Lipstick – Ruby Red / 50ml");
        assertThat(item.getSkuSnapshot()).isEqualTo("VML-RR-50");
        assertThat(item.getOptionLabelSnapshot()).isEqualTo("Ruby Red / 50ml");
        assertThat(item.getOptionColorSnapshot()).isEqualTo("Ruby Red");
        assertThat(item.getOptionSizeSnapshot()).isEqualTo("50ml");
        assertThat(item.getSwatchHexSnapshot()).isEqualTo("#9B111E");
        assertThat(item.getImageUrlSnapshot()).isEqualTo("https://res.cloudinary.com/demo/image/upload/ruby.jpg");

        // Staff rename everything after the sale: the line still says what the customer ordered.
        variant.getProduct().setName("Velvet Lip Colour");
        variant.setSku("VLC-001");
        variant.setVariantName("Velvet Lip Colour – Crimson / 30ml");
        variant.setOptionLabel("Crimson / 30ml");
        variant.setOptionColor("Crimson");
        variant.setOptionSize("30ml");
        variant.setSwatchHex("#DC143C");
        assertThat(item.soldSku()).isEqualTo("VML-RR-50");
        assertThat(item.soldProductName()).isEqualTo("Velvet Matte Lipstick");
        assertThat(item.soldOptionLabel()).isEqualTo("Ruby Red / 50ml");
        assertThat(item.soldOptionColor()).isEqualTo("Ruby Red");
        assertThat(item.soldOptionSize()).isEqualTo("50ml");
        assertThat(item.soldSwatchHex()).isEqualTo("#9B111E");
        assertThat(item.soldLabel()).isEqualTo("Velvet Matte Lipstick (Ruby Red / 50ml)");
    }

    @Test
    void aPackageLineAndEachOfItsComponentLinesFreezeTheirOwnVariantWithOneCoverLookup() {
        CartItem packageLine = cartItem(new BigDecimal("900.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false);
        packageLine.getVariant().getProduct().setName("Nail Tech Starter Package");
        packageLine.getVariant().setSku("PKG-0001");
        ProductVariant base = cartItem(new BigDecimal("300.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false).getVariant();
        base.getProduct().setName("Base Coat");
        base.setSku("BASE-15");
        base.setVariantName("15ml");
        ProductVariant polish = cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false).getVariant();
        polish.getProduct().setName("Gel Polish");
        polish.setSku("GEL-RR");
        polish.setVariantName("Gel Polish – Ruby Red");
        polish.setOptionColor("Ruby Red");
        polish.setSwatchHex("#9B111E");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(packageLine));
        when(packageService.selection(eq(packageLine.getVariant().getId()), any()))
                .thenReturn(new com.nbh.backend.packages.service.PackageService.Selection(
                        List.of(part(base, BigDecimal.ONE), part(polish, new BigDecimal("2"))), false));
        when(variantCovers.urlsFor(any())).thenReturn(java.util.Map.of(
                polish.getId(), "https://res.cloudinary.com/demo/image/upload/gel-rr.jpg"));

        List<CustomerOrderItem> items = checkoutService.checkout(SESSION_ID, request()).order().getItems();

        assertThat(items).as("the package's own line plus one line per component").hasSize(3);
        CustomerOrderItem summary = items.get(0);
        assertThat(summary.getSkuSnapshot()).isEqualTo("PKG-0001");
        assertThat(summary.getProductNameSnapshot()).isEqualTo("Nail Tech Starter Package");
        CustomerOrderItem baseLine = items.stream().filter(i -> i.getVariant() == base).findFirst().orElseThrow();
        assertThat(baseLine.getParentItem()).isSameAs(summary);
        assertThat(baseLine.getSkuSnapshot()).isEqualTo("BASE-15");
        assertThat(baseLine.getProductNameSnapshot()).isEqualTo("Base Coat");
        assertThat(baseLine.getImageUrlSnapshot()).as("no photo of its own: none is borrowed").isNull();
        CustomerOrderItem polishLine = items.stream().filter(i -> i.getVariant() == polish).findFirst().orElseThrow();
        assertThat(polishLine.getSkuSnapshot()).isEqualTo("GEL-RR");
        assertThat(polishLine.getOptionColorSnapshot()).isEqualTo("Ruby Red");
        assertThat(polishLine.getSwatchHexSnapshot()).isEqualTo("#9B111E");
        assertThat(polishLine.getImageUrlSnapshot()).endsWith("gel-rr.jpg");
        assertThat(polishLine.soldOptionLabel()).as("the option, with the product's title taken off").isEqualTo("Ruby Red");

        verify(variantCovers, times(1)).urlsFor(argThat(variants -> variants.size() == 3));
    }

    // Checkout only ever reads mode()/paymentWindow() from this, never the bank-detail accessors,
    // so the settings repository doesn't need a real row backing it for these tests.
    private static BankPaymentSettings bankSettings(BankPaymentSettings.CollectionMode mode) {
        return new BankPaymentSettings(
                mock(com.nbh.backend.payment.repository.PaymentSettingsRepository.class), mode, 45, "<ref>");
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
        verify(reservationService).reserve(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-01-01T00:45:00Z")));
    }

    @Test
    void mpesaModeKeepsTheDarajaRecordAndTheShortFifteenMinuteHold() {
        checkoutService = new CheckoutService(cartRepository, cartItemRepository,
                orderRepository, paymentRecordRepository, reservationService,
                orderNoGenerator, clock, eventPublisher, new PaymentReferenceGenerator(),
                bankSettings(BankPaymentSettings.CollectionMode.MPESA), MAX_OPEN_ORDERS, quoteService, packageService,
                variantCovers);
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        var payment = checkoutService.checkout(SESSION_ID, request()).paymentRecord();

        assertThat(payment.getProvider()).isEqualTo("DARAJA");
        assertThat(payment.getPaymentReference()).isNull();
        verify(reservationService).reserve(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-01-01T00:15:00Z")));
    }

    /** A product of a package as it comes (nothing picked instead): the package's own variant for it and the variant packed are one. */
    private static com.nbh.backend.packages.service.PackageService.SelectedPart part(ProductVariant variant, BigDecimal qtyPerPackage) {
        return new com.nbh.backend.packages.service.PackageService.SelectedPart(UUID.randomUUID(), variant, variant, qtyPerPackage, true);
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
        return new CheckoutRequest("Test Customer", "customer@example.com", "254700000000", FulfilmentType.PICKUP, null, null);
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
                FulfilmentType.PICKUP, null, "COURIER");

        assertThat(checkoutService.checkout(SESSION_ID, request).order().getDeliveryMethod()).isNull();
    }

    private CheckoutRequest tumabodaRequest(BigDecimal lat, BigDecimal lng) {
        return new CheckoutRequest("Njeri Test", "n@example.com", "0720 000 111", FulfilmentType.DELIVERY_COURIER,
                "Riverside Court, Kilimani", "BODA", lat, lng);
    }

    @Test
    void aTumabodaApiDeliveryAddsTheServersQuotedFeeToTheTotalAndKeepsTheDropoffPoint() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(quoteService.enabled()).thenReturn(true);
        when(quoteService.quote(any(), any(), any())).thenReturn(new com.nbh.backend.tumaboda.TumaBodaDtos.Quote(
                new BigDecimal("350.00"), new BigDecimal("50.00"), new BigDecimal("4.2"), 25));

        var order = checkoutService.checkout(SESSION_ID,
                tumabodaRequest(new BigDecimal("-1.2921"), new BigDecimal("36.8219"))).order();

        // The quote is for the products' value, and the customer is charged products + delivery.
        verify(quoteService).quote(new BigDecimal("-1.2921"), new BigDecimal("36.8219"), new BigDecimal("500.00"));
        assertThat(order.getDeliveryFee()).isEqualByComparingTo("350.00");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("850.00");
        assertThat(order.getDropoffLat()).isEqualByComparingTo("-1.2921");
        assertThat(order.isTumabodaApiDelivery()).isTrue();
    }

    @Test
    void aDropoffTumabodaCannotServeRefusesTheCheckoutBeforeAnyStockIsHeld() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(quoteService.enabled()).thenReturn(true);
        when(quoteService.quote(any(), any(), any())).thenThrow(
                new com.nbh.backend.tumaboda.TumaBodaException("OUT_OF_COVERAGE", "not covered", false));

        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID,
                tumabodaRequest(new BigDecimal("-1.2921"), new BigDecimal("36.8219"))))
                .isInstanceOf(com.nbh.backend.common.exception.UnprocessableException.class)
                .hasMessageContaining("TumaBoda can't deliver to that address");
        verify(reservationService, never()).reserve(any(), any(), any(), any(), any(), any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void tumabodaWithoutADropoffPointStaysPhoneArrangedAndIsNeverQuoted() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        lenient().when(quoteService.enabled()).thenReturn(true);

        var order = checkoutService.checkout(SESSION_ID, tumabodaRequest(null, null)).order();

        verify(quoteService, never()).quote(any(), any(), any());
        assertThat(order.getDeliveryFee()).isEqualByComparingTo("0");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("500.00");
        assertThat(order.isTumabodaApiDelivery()).isFalse();
    }

    @Test
    void withTheIntegrationOffADropoffPointIsIgnoredAndTheOrderIsPhoneArranged() {
        when(cartItemRepository.findByCartId(cart.getId()))
                .thenReturn(List.of(cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));
        when(quoteService.enabled()).thenReturn(false);

        var order = checkoutService.checkout(SESSION_ID,
                tumabodaRequest(new BigDecimal("-1.2921"), new BigDecimal("36.8219"))).order();

        verify(quoteService, never()).quote(any(), any(), any());
        assertThat(order.getDropoffLat()).isNull();
        assertThat(order.getGrandTotal()).isEqualByComparingTo("500.00");
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
    void aLineThatCannotBeReservedAbortsTheWholeCheckoutByPropagatingACustomerFriendlyException() {
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(
                cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false),
                cartItem(new BigDecimal("500.00"), BigDecimal.ONE, new BigDecimal("0.1600"), false)));

        when(reservationService.reserve(any(), any(), any(), any(), any(), any()))
                .thenReturn(new StockReservation())
                .thenThrow(new InsufficientAvailabilityException("Requested 1 of SKU-1234 but only 0 is available"));

        // The whole point of CheckoutService's catch block: the raw stock-service message never
        // reaches whoever sees this exception — a customer only needs to know an item ran out.
        assertThatThrownBy(() -> checkoutService.checkout(SESSION_ID, request()))
                .isInstanceOf(InsufficientAvailabilityException.class)
                .hasMessageContaining("Test product")
                .hasMessageContaining("out of stock")
                .hasMessageNotContaining("SKU-1234");
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
