package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.common.event.LowStockThresholdCrossedEvent;
import com.nbh.backend.stock.entity.*;
import com.nbh.backend.stock.exception.InsufficientStockException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockDocumentRepository;
import com.nbh.backend.stock.repository.StockLedgerEntryRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * §3.4-A: the moving-average and negative/reserved-floor guard logic in
 * {@link StockPostingService#post}, mocked so every branch runs in
 * milliseconds with no Postgres round trip. Each assertion is on a
 * RESULT — a returned/mutated field value, or an exception's type and
 * message — never on whether a mock method was merely called (§3.2).
 * The concurrency/locking properties themselves stay covered exclusively by
 * {@code ConcurrentPostingRaceTest} (§3.5) — a mock has no lock manager and
 * cannot be wrong in the way that test is wrong.
 *
 * <p>Branches/locations were removed 2026-09-24 (founder decision — see
 * HANDOFF.md): SHOP replaces every internal location, SUPPLIER/CUSTOMER/
 * ADJUSTMENT stay as the virtual double-entry counterparts.
 */
@ExtendWith(MockitoExtension.class)
class StockPostingServiceUnitTest {

    @Mock private StockDocumentRepository documentRepository;
    @Mock private StockLedgerEntryRepository ledgerRepository;
    @Mock private StockBalanceRepository balanceRepository;
    @Mock private StockReservationRepository reservationRepository;
    @Mock private StockDocumentNoGenerator documentNoGenerator;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private StockPostingService postingService;

    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        postingService = new StockPostingService(documentRepository, ledgerRepository, balanceRepository, reservationRepository, documentNoGenerator, clock, eventPublisher);

        // Not every test reaches these — the reservation-account/variant guards run
        // before saveAndFlush, and the negative-stock/reserved-floor guard tests
        // throw before the others are called. lenient() so Mockito's strict-stubs
        // check doesn't fail those tests over stubs they never use.
        lenient().when(documentRepository.saveAndFlush(any(StockDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentRepository.save(any(StockDocument.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(ledgerRepository.save(any(StockLedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(balanceRepository.save(any(StockBalance.class))).thenAnswer(inv -> inv.getArgument(0));

        variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setSku("LOTION-500");
        variant.setVariantName("500ml");
        variant.setSellingPrice(new BigDecimal("999.00"));
    }

    private StockBalance balanceOf(BigDecimal onHand, BigDecimal reserved, BigDecimal avgCost) {
        StockBalance balance = new StockBalance();
        balance.setOnHandQty(onHand);
        balance.setReservedQty(reserved);
        balance.setAvgCost(avgCost);
        balance.setStockValue(onHand.multiply(avgCost));
        return balance;
    }

    /** A GOODS_RECEIPT-shaped document with one inbound line (target only, no source). */
    private StockDocument inboundDoc(BigDecimal qty, BigDecimal unitCost) {
        StockDocument document = new StockDocument();
        document.setDocumentNo("GRN-TEST-" + UUID.randomUUID());
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setTargetAccount(StockAccount.SHOP);
        line.setQty(qty);
        line.setUnitCost(unitCost);
        document.getLines().add(line);
        return document;
    }

    /** A WRITE_OFF-shaped document with one outbound line (source only, no target). */
    private StockDocument outboundDoc(BigDecimal qty, StockReservation fulfils) {
        StockDocument document = new StockDocument();
        document.setDocumentNo("WO-TEST-" + UUID.randomUUID());
        document.setDocumentType(StockDocumentType.WRITE_OFF);

        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SHOP);
        line.setQty(qty);
        line.setReservation(fulfils);
        document.getLines().add(line);
        return document;
    }

    @Test
    void movingAverageWhenOnHandIsCurrentlyZero() {
        StockBalance balance = balanceOf(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(inboundDoc(BigDecimal.TEN, new BigDecimal("500")));

        assertThat(balance.getAvgCost()).isEqualByComparingTo("500.0000");
        assertThat(balance.getOnHandQty()).isEqualByComparingTo("10");
    }

    @Test
    void movingAverageBlendsCorrectly() {
        StockBalance balance = balanceOf(new BigDecimal("50"), BigDecimal.ZERO, new BigDecimal("900"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(inboundDoc(new BigDecimal("50"), new BigDecimal("1100")));

        assertThat(balance.getAvgCost()).isEqualByComparingTo("1000.0000");
        assertThat(balance.getStockValue()).isEqualByComparingTo("100000.0000");
        assertThat(balance.getOnHandQty()).isEqualByComparingTo("100");
    }

    @Test
    void movingAverageRoundsHalfUpAtScaleFour() {
        StockBalance balance = balanceOf(new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("100"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(inboundDoc(BigDecimal.ONE, new BigDecimal("101")));

        assertThat(balance.getAvgCost()).isEqualByComparingTo("100.2500");
    }

    @Test
    void inboundLineWithNoUnitCostFallsBackToCurrentAverage() {
        StockBalance balance = balanceOf(BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal("250"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(inboundDoc(new BigDecimal("5"), null));

        assertThat(balance.getAvgCost()).isEqualByComparingTo("250.0000");
    }

    @Test
    void negativeStockGuardFiresOnShop() {
        StockBalance balance = balanceOf(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> postingService.post(outboundDoc(new BigDecimal("2"), null)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("LOTION-500");

        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void negativeStockGuardDoesNotFireOnAVirtualAccount() {
        StockBalance balance = balanceOf(new BigDecimal("0"), BigDecimal.ZERO, new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        StockDocument document = new StockDocument();
        document.setDocumentNo("GRN-TEST-" + UUID.randomUUID());
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SUPPLIER); // virtual — negative allowed
        line.setQty(new BigDecimal("50"));
        document.getLines().add(line);

        postingService.post(document);

        assertThat(balance.getOnHandQty()).isEqualByComparingTo("-50");
    }

    @Test
    void reservedFloorBlocksAnUnreservedOutbound() {
        StockBalance balance = balanceOf(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> postingService.post(outboundDoc(BigDecimal.ONE, null)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("held for customers' orders")
                .hasMessageContaining("the count can't go below 1");
    }

    @Test
    void reservationExactlyMatchingAvailableHasZeroSlackInBothDirections() {
        StockBalance succeedBalance = balanceOf(new BigDecimal("5"), new BigDecimal("2"), new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(succeedBalance));
        postingService.post(outboundDoc(new BigDecimal("3"), null));
        assertThat(succeedBalance.getOnHandQty()).isEqualByComparingTo("2");

        StockBalance failBalance = balanceOf(new BigDecimal("5"), new BigDecimal("2"), new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(failBalance));
        assertThatThrownBy(() -> postingService.post(outboundDoc(new BigDecimal("4"), null)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void lineFulfillingItsOwnReservationIsExemptFromTheFloor() {
        StockBalance balance = balanceOf(new BigDecimal("3"), new BigDecimal("3"), new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        StockReservation reservation = new StockReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setVariant(variant);
        reservation.setQty(new BigDecimal("3"));
        reservation.setFulfilledQty(BigDecimal.ZERO);
        reservation.setReleasedQty(BigDecimal.ZERO);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);
        when(reservationRepository.lockById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        postingService.post(outboundDoc(new BigDecimal("3"), reservation));

        assertThat(balance.getOnHandQty()).isEqualByComparingTo("0");
        assertThat(balance.getReservedQty()).isEqualByComparingTo("0");
        assertThat(reservation.getStatus()).isEqualTo(StockReservation.ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledQty()).isEqualByComparingTo("3");
    }

    @Test
    void aLineFulfillingAReservationMustSourceFromShop() {
        StockReservation reservation = new StockReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setVariant(variant);
        reservation.setQty(BigDecimal.ONE);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);

        StockDocument document = new StockDocument();
        document.setDocumentNo("BAD-" + UUID.randomUUID());
        document.setDocumentType(StockDocumentType.ADJUSTMENT);
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.ADJUSTMENT); // not SHOP — must be rejected
        line.setQty(BigDecimal.ONE);
        line.setReservation(reservation);
        document.getLines().add(line);

        assertThatThrownBy(() -> postingService.post(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHOP");
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void aLineFulfillingAReservationMustMatchTheReservationsVariant() {
        ProductVariant otherVariant = new ProductVariant();
        otherVariant.setId(UUID.randomUUID());
        otherVariant.setSku("OTHER-SKU");

        StockReservation reservation = new StockReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setVariant(otherVariant);
        reservation.setQty(BigDecimal.ONE);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);
        // Phase 0 fix (2026-09-27): the variant-match check now runs after both the balance
        // and the reservation are locked (not off the raw line.getReservation() reference —
        // see post()'s comment on why), so this test must let posting reach that point:
        // stub the balance lock the same generic way the other tests in this file do, and
        // the reservation findable via lockById the same way a real one would be.
        when(balanceRepository.lockByVariantIdAndAccount(any(), any()))
                .thenReturn(Optional.of(balanceOf(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE)));
        when(reservationRepository.lockById(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> postingService.post(outboundDoc(BigDecimal.ONE, reservation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variant");
        verifyNoInteractions(ledgerRepository);
    }

    @Test
    void outboundCapturesTheCurrentAverageAsTheEntrysUnitCost() {
        StockBalance balance = balanceOf(new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("900"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        ArgumentCaptor<StockLedgerEntry> captor = ArgumentCaptor.forClass(StockLedgerEntry.class);

        postingService.post(outboundDoc(new BigDecimal("3"), null));

        verify(ledgerRepository).save(captor.capture());
        StockLedgerEntry entry = captor.getValue();
        assertThat(entry.getUnitCost()).isEqualByComparingTo("900");
        assertThat(entry.getAvgCostAfter()).isEqualByComparingTo("900.0000");
        assertThat(entry.getStockValueDifference()).isEqualByComparingTo("-2700");
        assertThat(entry.getQtyChange()).isEqualByComparingTo("-3");
    }

    @Test
    void lowStockEventFiresExactlyOnceOnTheMovementThatCrossesTheThreshold() {
        variant.setReorderLevel(new BigDecimal("5"));
        StockBalance balance = balanceOf(BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(outboundDoc(new BigDecimal("6"), null)); // 10 -> 4, crosses below 5

        ArgumentCaptor<LowStockThresholdCrossedEvent> captor = ArgumentCaptor.forClass(LowStockThresholdCrossedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        LowStockThresholdCrossedEvent event = captor.getValue();
        assertThat(event.sku()).isEqualTo("LOTION-500");
        assertThat(event.onHandBefore()).isEqualByComparingTo("10");
        assertThat(event.onHandAfter()).isEqualByComparingTo("4");
        assertThat(event.reorderLevel()).isEqualByComparingTo("5");
    }

    /**
     * §3.2a's own explicit guidance: the highest-value test for an
     * edge-triggered event is the NEGATIVE one — the only thing that pins
     * edge-triggered vs. level-triggered behaviour apart. Without this test,
     * a regression to "fire whenever on_hand < reorderLevel" (dozens of
     * identical alerts per hour during a promotion) would pass every other
     * assertion in this class.
     */
    @Test
    void lowStockEventDoesNotFireAgainOnASubsequentSaleOfAnAlreadyLowSku() {
        variant.setReorderLevel(new BigDecimal("5"));
        StockBalance balance = balanceOf(new BigDecimal("4"), BigDecimal.ZERO, new BigDecimal("300")); // already below 5
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        postingService.post(outboundDoc(BigDecimal.ONE, null)); // 4 -> 3, still below 5, not a new crossing

        verify(eventPublisher, never()).publishEvent(any(LowStockThresholdCrossedEvent.class));
    }

    @Test
    void lowStockEventDoesNotFireOnAVirtualAccount() {
        variant.setReorderLevel(new BigDecimal("5"));
        StockBalance balance = balanceOf(new BigDecimal("0"), BigDecimal.ZERO, new BigDecimal("300"));
        when(balanceRepository.lockByVariantIdAndAccount(any(), any())).thenReturn(Optional.of(balance));

        StockDocument document = new StockDocument();
        document.setDocumentNo("GRN-TEST-" + UUID.randomUUID());
        document.setDocumentType(StockDocumentType.GOODS_RECEIPT);
        StockDocumentLine line = new StockDocumentLine();
        line.setDocument(document);
        line.setLineNo(1);
        line.setVariant(variant);
        line.setSourceAccount(StockAccount.SUPPLIER); // virtual — SUPPLIER going more negative is not "low stock"
        line.setQty(new BigDecimal("50"));
        document.getLines().add(line);

        postingService.post(document);

        verify(eventPublisher, never()).publishEvent(any(LowStockThresholdCrossedEvent.class));
    }
}
