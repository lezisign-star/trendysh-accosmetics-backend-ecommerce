package com.nbh.backend.live;

import com.nbh.backend.common.event.BankCreditRecordedEvent;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.event.OrderCancelledEvent;
import com.nbh.backend.common.event.OrderDeliveredEvent;
import com.nbh.backend.common.event.OrderFulfilledEvent;
import com.nbh.backend.common.event.OrderPickingStartedEvent;
import com.nbh.backend.common.event.OrderPlacedEvent;
import com.nbh.backend.common.event.OrderReadyEvent;
import com.nbh.backend.common.event.PaymentClaimSubmittedEvent;
import com.nbh.backend.common.event.PaymentConfirmedEvent;
import com.nbh.backend.common.event.PaymentFailedEvent;
import com.nbh.backend.common.event.TumaBodaDeliveryUpdatedEvent;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.payment.entity.PaymentRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Every domain event {@link LiveUpdateListener} handles must reach the right order's watchers (a bare
 * "something changed", never details) and, except where noted, tell staff what kind of thing happened.
 * Called directly rather than through Spring's event bus — {@code @TransactionalEventListener}'s own
 * commit-timing behaviour is exercised where it matters (real transactions) elsewhere; here only the
 * routing from event to {@link LiveStreams} call needs proving.
 */
class LiveUpdateListenerTest {

    private final LiveStreams streams = mock(LiveStreams.class);
    private final LiveUpdateListener listener = new LiveUpdateListener(streams);

    @Test
    void orderPlacedTellsStaffTheAmountAndTellsTheOrdersWatchers() {
        listener.onOrderPlaced(new OrderPlacedEvent(UUID.randomUUID(), "SO-1", "a@b.com", "254700000000",
                new BigDecimal("1500"), 2));

        verify(streams).publishToStaff(new AdminEvent(AdminEvent.Type.ORDER_PLACED, "SO-1", new BigDecimal("1500")));
        verify(streams).publishToOrder("SO-1");
        verifyNoMoreInteractions(streams);
    }

    @Test
    void paymentConfirmedTellsStaffTheAmountAndTellsTheOrdersWatchers() {
        listener.onPaymentConfirmed(new PaymentConfirmedEvent(UUID.randomUUID(), UUID.randomUUID(), "SO-1",
                new BigDecimal("500"), PaymentRecord.PaymentMethod.BANK, "REF-1"));

        verify(streams).publishToStaff(new AdminEvent(AdminEvent.Type.PAYMENT_CONFIRMED, "SO-1", new BigDecimal("500")));
        verify(streams).publishToOrder("SO-1");
    }

    @Test
    void paymentFailedCarriesNoAmountButStillTellsTheOrdersWatchers() {
        listener.onPaymentFailed(new PaymentFailedEvent(UUID.randomUUID(), UUID.randomUUID(), "SO-1", "insufficient funds"));

        verify(streams).publishToStaff(argThat(e -> e.type() == AdminEvent.Type.PAYMENT_FAILED
                && e.orderNo().equals("SO-1") && e.amount() == null));
        verify(streams).publishToOrder("SO-1");
    }

    @Test
    void everyOrderLifecycleEventBecomesAnOrderUpdatedHintToStaffAndTheOrder() {
        listener.onOrderPickingStarted(new OrderPickingStartedEvent(UUID.randomUUID(), "SO-1"));
        listener.onOrderReady(new OrderReadyEvent(UUID.randomUUID(), "SO-1", FulfilmentType.PICKUP));
        listener.onOrderFulfilled(new OrderFulfilledEvent(UUID.randomUUID(), "SO-1", FulfilmentType.PICKUP, null));
        listener.onOrderDelivered(new OrderDeliveredEvent(UUID.randomUUID(), "SO-1"));
        listener.onOrderCancelled(new OrderCancelledEvent(UUID.randomUUID(), "SO-1", "customer asked"));

        verify(streams, org.mockito.Mockito.times(5)).publishToStaff(AdminEvent.of(AdminEvent.Type.ORDER_UPDATED, "SO-1"));
        verify(streams, org.mockito.Mockito.times(5)).publishToOrder("SO-1");
    }

    @Test
    void paymentClaimSubmittedTellsStaffTheClaimedAmountAndTellsTheOrdersWatchers() {
        listener.onPaymentClaimSubmitted(new PaymentClaimSubmittedEvent(UUID.randomUUID(), UUID.randomUUID(), "SO-1",
                new BigDecimal("500")));

        verify(streams).publishToStaff(new AdminEvent(AdminEvent.Type.PAYMENT_CLAIMED, "SO-1", new BigDecimal("500")));
        verify(streams).publishToOrder("SO-1");
    }

    @Test
    void aMatchedBankCreditNamesTheOrderToStaffButNeverPingsAnOrderWatcher() {
        listener.onBankCreditRecorded(new BankCreditRecordedEvent(UUID.randomUUID(), new BigDecimal("500"), true, "SO-1"));

        verify(streams).publishToStaff(new AdminEvent(AdminEvent.Type.BANK_CREDIT, "SO-1", new BigDecimal("500")));
        verify(streams, never()).publishToOrder(eq("SO-1"));
    }

    @Test
    void anUnmatchedBankCreditHasNoOrderAtAllYet() {
        listener.onBankCreditRecorded(new BankCreditRecordedEvent(UUID.randomUUID(), new BigDecimal("500"), false, null));

        verify(streams).publishToStaff(argThat(e -> e.type() == AdminEvent.Type.BANK_CREDIT && e.orderNo() == null));
        verify(streams, never()).publishToOrder(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tumaBodaDeliveryUpdatedTellsStaffAndTheOrdersWatchers() {
        listener.onTumaBodaDeliveryUpdated(new TumaBodaDeliveryUpdatedEvent(UUID.randomUUID(), "SO-1", "in_transit"));

        verify(streams).publishToStaff(AdminEvent.of(AdminEvent.Type.DELIVERY_UPDATED, "SO-1"));
        verify(streams).publishToOrder("SO-1");
    }

    // ---- shared-data changes: staff are told quickly, storefront visitors in slower, coalesced batches ----

    @Test
    void aBurstOfWritesToOneTopicReachesVisitorsAsASingleHint() {
        for (int i = 0; i < 500; i++) {
            listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.STOCK));
        }

        listener.flushPublicDataChanges();
        listener.flushPublicDataChanges(); // nothing new since — must not repeat

        verify(streams, times(1)).publishToPublic("STOCK");
    }

    @Test
    void eachChangedTopicGetsItsOwnHintAndAQuietTopicGetsNone() {
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));

        listener.flushPublicDataChanges();

        verify(streams).publishToPublic("PRODUCTS");
        verify(streams).publishToPublic("CATALOG");
        verify(streams, never()).publishToPublic("STOCK");
    }

    @Test
    void staffDashboardsAreFlushedOnTheirOwnFastPathAndVisitorsAreNotToldByIt() {
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));

        listener.flushDataChanges();

        verify(streams).publishToStaff(AdminEvent.dataChanged("PRODUCTS"));
        verify(streams, never()).publishToPublic(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void flushingForVisitorsDoesNotConsumeWhatStaffHaveYetToBeTold() {
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));

        listener.flushPublicDataChanges();
        listener.flushDataChanges();

        verify(streams).publishToPublic("PRODUCTS");
        verify(streams).publishToStaff(AdminEvent.dataChanged("PRODUCTS"));
    }

    @Test
    void staffOnlyTopicsNeverReachStorefrontVisitors() {
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.DELIVERY_SUGGESTIONS));
        listener.onDataChanged(new DataChangedEvent(DataChangedEvent.Topic.IMAGE_CLEANUP));

        listener.flushPublicDataChanges();
        listener.flushDataChanges();

        verify(streams, never()).publishToPublic(org.mockito.ArgumentMatchers.any());
        verify(streams).publishToStaff(AdminEvent.dataChanged("DELIVERY_SUGGESTIONS"));
        verify(streams).publishToStaff(AdminEvent.dataChanged("IMAGE_CLEANUP"));
    }
}
