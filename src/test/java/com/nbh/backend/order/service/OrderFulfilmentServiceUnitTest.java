package com.nbh.backend.order.service;

import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderDeliveredEvent;
import com.nbh.backend.common.event.OrderPickingStartedEvent;
import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockAvailabilityService;
import com.nbh.backend.stock.service.StockDocumentNoGenerator;
import com.nbh.backend.stock.service.StockPostingService;
import com.nbh.backend.stock.service.StockReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@code OrderFulfilmentService.cancel} — new in the integration-readiness
 * plan's Phase 1.2 (the frontend's admin orders screen assumed a cancel
 * action existed; it didn't). Result-driven per this project's own standard:
 * asserts on the returned order's status and on which reservations were
 * actually released, never on a bare mock-call count.
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfilmentServiceUnitTest {

    @Mock private CustomerOrderRepository orderRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private StockPostingService postingService;
    @Mock private StockDocumentNoGenerator documentNoGenerator;
    @Mock private StockReservationService reservationService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private OrderFulfilmentService fulfilmentService;
    private static final UUID ORDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fulfilmentService = new OrderFulfilmentService(orderRepository, availabilityService,
                postingService, documentNoGenerator, reservationService, eventPublisher);
    }

    @Test
    void cancellingAPendingPaymentOrderReleasesItsActiveReservationAndPublishesTheEvent() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        StockReservation activeReservation = new StockReservation();
        activeReservation.setId(UUID.randomUUID());
        activeReservation.setStatus(StockReservation.ReservationStatus.ACTIVE);

        CustomerOrderItem item = new CustomerOrderItem();
        item.setReservation(activeReservation);

        CustomerOrder order = orderWithStatus(OrderStatus.PENDING_PAYMENT, List.of(item));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.cancel(ORDER_ID, "customer changed their mind");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(reservationService).release(activeReservation.getId());

        ArgumentCaptor<OrderCancelledEvent> captor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().reason()).isEqualTo("customer changed their mind");
    }

    @Test
    void cancellingAPaidOrderIsAllowed() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.PAID, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.cancel(ORDER_ID, null);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingADispatchedOrderThrowsAndNeverTouchesReservationsOrStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.DISPATCHED, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> fulfilmentService.cancel(ORDER_ID, "too late"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("DISPATCHED");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DISPATCHED);
        verifyNoInteractions(reservationService, eventPublisher);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancellingAPickingOrderIsAllowed() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.PICKING, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.cancel(ORDER_ID, null);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingAReadyOrderIsAllowed() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.READY, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.cancel(ORDER_ID, null);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void startingPickingOnAPaidOrderMovesItToPickingAndPublishesTheEvent() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.PAID, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.startPicking(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.PICKING);
        ArgumentCaptor<OrderPickingStartedEvent> captor = ArgumentCaptor.forClass(OrderPickingStartedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void startingPickingOnAPendingPaymentOrderThrowsAndNeverTouchesStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.PENDING_PAYMENT, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> fulfilmentService.startPicking(ORDER_ID))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("PENDING_PAYMENT");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        verifyNoInteractions(eventPublisher);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void markingReadyFromPickingMovesItToReadyAndPublishesTheEvent() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.PICKING, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.markReady(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.READY);
        verify(eventPublisher).publishEvent(any(OrderReadyEvent.class));
    }

    @Test
    void markingReadyFromPaidDirectlySkipsPicking() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.PAID, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.markReady(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.READY);
    }

    @Test
    void markingReadyFromDispatchedThrows() {
        CustomerOrder order = orderWithStatus(OrderStatus.DISPATCHED, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> fulfilmentService.markReady(ORDER_ID))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("DISPATCHED");

        verify(orderRepository, never()).save(any());
    }

    @Test
    void markingDeliveredFromDispatchedMovesItToDeliveredAndPublishesTheEvent() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerOrder order = orderWithStatus(OrderStatus.DISPATCHED, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        CustomerOrder result = fulfilmentService.markDelivered(ORDER_ID);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        ArgumentCaptor<OrderDeliveredEvent> captor = ArgumentCaptor.forClass(OrderDeliveredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().orderNo()).isEqualTo("SO-TEST-000001");
    }

    @Test
    void markingDeliveredBeforeDispatchThrowsAndNeverTouchesStatus() {
        CustomerOrder order = orderWithStatus(OrderStatus.PAID, List.of());
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> fulfilmentService.markDelivered(ORDER_ID))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("must be DISPATCHED first");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(eventPublisher);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancellingDoesNotReReleaseAReservationThatIsAlreadyTerminal() {
        when(orderRepository.save(any(CustomerOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        StockReservation alreadyReleased = new StockReservation();
        alreadyReleased.setId(UUID.randomUUID());
        alreadyReleased.setStatus(StockReservation.ReservationStatus.RELEASED);

        CustomerOrderItem item = new CustomerOrderItem();
        item.setReservation(alreadyReleased);

        CustomerOrder order = orderWithStatus(OrderStatus.PENDING_PAYMENT, List.of(item));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        fulfilmentService.cancel(ORDER_ID, null);

        verify(reservationService, never()).release(any());
    }

    private CustomerOrder orderWithStatus(OrderStatus status, List<CustomerOrderItem> items) {
        CustomerOrder order = new CustomerOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("SO-TEST-000001");
        order.setStatus(status);
        order.getItems().addAll(items);
        return order;
    }
}
