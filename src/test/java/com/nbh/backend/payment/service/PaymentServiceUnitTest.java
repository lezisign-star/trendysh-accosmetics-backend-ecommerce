package com.nbh.backend.payment.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.payment.entity.PaymentRecord;
import com.nbh.backend.payment.repository.PaymentRecordRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockReservationService;
import jakarta.persistence.EntityNotFoundException;
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
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * §3.3 item 4: {@code PaymentService.confirmSuccess}/{@code markFailed} — a
 * state machine with guards, pure logic, previously zero test coverage, and
 * the code that moves an order to PAID. The concurrent-write property the §4
 * C2 fix closes is proven separately by {@code ConcurrentPaymentConfirmationRaceTest}
 * (a mock has no row lock and cannot be wrong in that way); this class pins
 * the guard/outcome logic itself.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceUnitTest {

    @Mock private PaymentRecordRepository paymentRecordRepository;
    @Mock private CustomerOrderRepository orderRepository;
    @Mock private StockReservationService reservationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-03-15T09:30:00Z"), ZoneOffset.UTC);
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRecordRepository, orderRepository, reservationService, fixedClock, eventPublisher);
        lenient().when(paymentRecordRepository.save(any(PaymentRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PaymentRecord paymentWith(PaymentRecord.PaymentStatus status, CustomerOrder order) {
        PaymentRecord payment = new PaymentRecord();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setProvider("DARAJA");
        payment.setMethod(PaymentRecord.PaymentMethod.MPESA);
        payment.setAmount(new BigDecimal("500.00"));
        payment.setStatus(status);
        return payment;
    }

    private CustomerOrder orderWithItems(StockReservation... reservations) {
        CustomerOrder order = new CustomerOrder();
        order.setId(UUID.randomUUID());
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setItems(new ArrayList<>());
        for (StockReservation reservation : reservations) {
            CustomerOrderItem item = new CustomerOrderItem();
            item.setOrder(order);
            ProductVariant variant = new ProductVariant();
            variant.setId(UUID.randomUUID());
            item.setVariant(variant);
            item.setReservation(reservation);
            order.getItems().add(item);
        }
        return order;
    }

    /** confirmForOrder returns the hold now backing the order line — the same one unless it had lapsed. */
    private void confirmingKeepsTheSameHold(StockReservation... reservations) {
        for (StockReservation reservation : reservations) {
            lenient().when(reservationService.confirmForOrder(reservation.getId())).thenReturn(reservation);
        }
    }

    private StockReservation reservation() {
        StockReservation reservation = new StockReservation();
        reservation.setId(UUID.randomUUID());
        return reservation;
    }

    @Test
    void confirmSuccessFromInitiatedConfirmsEveryReservationAndMarksOrderPaid() {
        StockReservation r1 = reservation();
        StockReservation r2 = reservation();
        confirmingKeepsTheSameHold(r1, r2);
        CustomerOrder order = orderWithItems(r1, r2);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentRecord result = paymentService.confirmSuccess(payment.getId(), "MPESA-REF-1", "{}");

        assertThat(result.getStatus()).isEqualTo(PaymentRecord.PaymentStatus.SUCCESS);
        assertThat(result.getProviderReference()).isEqualTo("MPESA-REF-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPlacedAt()).isEqualTo(fixedClock.instant());
        verify(reservationService).confirmForOrder(r1.getId());
        verify(reservationService).confirmForOrder(r2.getId());
    }

    @Test
    void aHoldThatLapsedBeforeStaffConfirmedIsReplacedByTheOneTheReservationServiceRetook() {
        StockReservation lapsed = reservation();
        StockReservation retaken = reservation();
        when(reservationService.confirmForOrder(lapsed.getId())).thenReturn(retaken);
        CustomerOrder order = orderWithItems(lapsed);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        paymentService.confirmSuccess(payment.getId(), "LATE-REF", "{}");

        // The order line must point at the live hold, or dispatch would later try to consume a dead one.
        assertThat(order.getItems().get(0).getReservation()).isSameAs(retaken);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void aCancelledOrderCannotBeMarkedPaidAndNothingIsTouched() {
        CustomerOrder order = orderWithItems(reservation());
        order.setOrderNo("SO-CANCELLED-1");
        order.setStatus(OrderStatus.CANCELLED);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.confirmSuccess(payment.getId(), "REF", "{}"))
                .isInstanceOf(com.nbh.backend.common.exception.ConflictException.class)
                .hasMessageContaining("SO-CANCELLED-1")
                .hasMessageContaining("refund");

        verifyNoInteractions(reservationService);
        assertThat(payment.getStatus()).isEqualTo(PaymentRecord.PaymentStatus.INITIATED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void manualConfirmationRecordsTheMethodTheCustomerActuallyUsed() {
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, orderWithItems());
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentRecord result = paymentService.confirmSuccess(
                payment.getId(), "BANK-REF", "{}", PaymentRecord.PaymentMethod.BANK);

        assertThat(result.getMethod()).isEqualTo(PaymentRecord.PaymentMethod.BANK);
        assertThat(result.getStatus()).isEqualTo(PaymentRecord.PaymentStatus.SUCCESS);
    }

    @Test
    void confirmationWithoutAMethodKeepsTheRecordsOwnMethod() {
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, orderWithItems());
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentRecord result = paymentService.confirmSuccess(payment.getId(), "REF", "{}", null);

        assertThat(result.getMethod()).isEqualTo(PaymentRecord.PaymentMethod.MPESA);
    }

    @Test
    void confirmSuccessSkipsItemsWithNoReservation() {
        CustomerOrder order = orderWithItems((StockReservation) null);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.PENDING, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        paymentService.confirmSuccess(payment.getId(), "MPESA-REF-2", "{}");

        verifyNoInteractions(reservationService);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void confirmSuccessIsIdempotentWhenAlreadySuccessAndTouchesNothingElse() {
        CustomerOrder order = orderWithItems(reservation());
        order.setStatus(OrderStatus.PAID);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.SUCCESS, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentRecord result = paymentService.confirmSuccess(payment.getId(), "IGNORED", "{}");

        assertThat(result).isSameAs(payment);
        assertThat(result.getStatus()).isEqualTo(PaymentRecord.PaymentStatus.SUCCESS);
        verifyNoInteractions(reservationService);
        verify(orderRepository, never()).save(any());
        verify(paymentRecordRepository, never()).save(any());
    }

    @Test
    void confirmSuccessFromATerminalFailedStateThrowsAndTouchesNothing() {
        CustomerOrder order = orderWithItems(reservation());
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.FAILED, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.confirmSuccess(payment.getId(), "REF", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot confirm success from this state");

        verifyNoInteractions(reservationService);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void confirmSuccessOnAnUnknownPaymentThrowsEntityNotFound() {
        UUID missingId = UUID.randomUUID();
        when(paymentRecordRepository.lockById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.confirmSuccess(missingId, "REF", "{}"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void markFailedFromInitiatedReleasesEveryReservationAndMarksOrderPaymentFailed() {
        StockReservation r1 = reservation();
        StockReservation r2 = reservation();
        CustomerOrder order = orderWithItems(r1, r2);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.INITIATED, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        PaymentRecord result = paymentService.markFailed(payment.getId(), "Daraja ResultCode 1", "{}");

        assertThat(result.getStatus()).isEqualTo(PaymentRecord.PaymentStatus.FAILED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        verify(reservationService).release(r1.getId());
        verify(reservationService).release(r2.getId());
    }

    @Test
    void markFailedWhenAlreadySucceededThrowsAndNeverReleasesStock() {
        CustomerOrder order = orderWithItems(reservation());
        order.setStatus(OrderStatus.PAID);
        PaymentRecord payment = paymentWith(PaymentRecord.PaymentStatus.SUCCESS, order);
        when(paymentRecordRepository.lockById(payment.getId())).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.markFailed(payment.getId(), "late failure callback", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already succeeded — cannot mark failed");

        // The exact corruption §4 C2 describes: a stale-snapshot markFailed must
        // never reach the point of releasing a paid order's stock.
        verifyNoInteractions(reservationService);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void markFailedOnAnUnknownPaymentThrowsEntityNotFound() {
        UUID missingId = UUID.randomUUID();
        when(paymentRecordRepository.lockById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.markFailed(missingId, "reason", "{}"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
