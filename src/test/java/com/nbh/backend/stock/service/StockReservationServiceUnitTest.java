package com.nbh.backend.stock.service;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.stock.entity.StockBalance;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.exception.InsufficientAvailabilityException;
import com.nbh.backend.stock.repository.StockBalanceRepository;
import com.nbh.backend.stock.repository.StockReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * §3.4-C: {@link StockReservationService#reserve}'s availability arithmetic,
 * mocked. {@code ConcurrentReservationRaceTest} proves exactly one concurrent
 * caller wins under real Postgres locking; it does not pin what "available"
 * actually means at the boundary, or the expiry-filtering semantics — that is
 * what this class covers instead, in milliseconds with no database.
 */
@ExtendWith(MockitoExtension.class)
class StockReservationServiceUnitTest {

    @Mock private StockBalanceRepository balanceRepository;
    @Mock private StockReservationRepository reservationRepository;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private StockReservationService reservationService;

    private ProductVariant variant;
    private StockLocation location;

    @BeforeEach
    void setUp() {
        reservationService = new StockReservationService(balanceRepository, reservationRepository, fixedClock);

        variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setSku("RESV-TEST-SKU");
        variant.setVariantName("Test variant");
        variant.setSellingPrice(new BigDecimal("500.00"));

        location = new StockLocation();
        location.setId(UUID.randomUUID());
        location.setCode("BR-A/SALES_FLOOR");

        lenient().when(reservationRepository.save(any(StockReservation.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(balanceRepository.save(any(StockBalance.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private StockBalance balanceWithOnHand(BigDecimal onHand) {
        StockBalance balance = new StockBalance();
        balance.setOnHandQty(onHand);
        balance.setReservedQty(BigDecimal.ZERO);
        return balance;
    }

    private StockReservation activeHold(BigDecimal qty, BigDecimal fulfilledQty, Instant expiresAt) {
        StockReservation reservation = new StockReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setQty(qty);
        reservation.setFulfilledQty(fulfilledQty);
        reservation.setReleasedQty(BigDecimal.ZERO);
        reservation.setStatus(StockReservation.ReservationStatus.ACTIVE);
        reservation.setExpiresAt(expiresAt);
        return reservation;
    }

    @Test
    void reservationExactlyMatchingAvailableSucceedsAndOneMoreThrows() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(balanceWithOnHand(BigDecimal.TEN)));
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("4"), BigDecimal.ZERO, null)));

        StockReservation result = reservationService.reserve(
                variant, location, new BigDecimal("6"), StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null);

        assertThat(result.getQty()).isEqualByComparingTo("6");
        assertThat(result.getStatus()).isEqualTo(StockReservation.ReservationStatus.ACTIVE);
        assertThat(result.getSource()).isEqualTo(StockReservation.ReservationSource.ONLINE_CART);

        // Symmetric case: one more than available must throw, pinning the boundary from both sides.
        StockBalance failBalance = balanceWithOnHand(BigDecimal.TEN);
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(failBalance));
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("4"), BigDecimal.ZERO, null)));

        assertThatThrownBy(() -> reservationService.reserve(
                variant, location, new BigDecimal("7"), StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null))
                .isInstanceOf(InsufficientAvailabilityException.class)
                .hasMessageContaining("6")
                .hasMessageContaining("RESV-TEST-SKU");
    }

    @Test
    void anExpiredHoldDoesNotConsumeAvailability() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(balanceWithOnHand(BigDecimal.TEN)));
        Instant expiredInThePast = fixedClock.instant().minusSeconds(60);
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("8"), BigDecimal.ZERO, expiredInThePast)));

        StockReservation result = reservationService.reserve(
                variant, location, new BigDecimal("9"), StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null);

        assertThat(result.getQty()).isEqualByComparingTo("9");
    }

    @Test
    void aNullExpiryHoldAlwaysConsumesAvailability() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(balanceWithOnHand(BigDecimal.TEN)));
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("8"), BigDecimal.ZERO, null)));

        assertThatThrownBy(() -> reservationService.reserve(
                variant, location, new BigDecimal("3"), StockReservation.ReservationSource.ONLINE_ORDER,
                "CUSTOMER_ORDER", UUID.randomUUID(), null))
                .isInstanceOf(InsufficientAvailabilityException.class);
    }

    @Test
    void aHoldExpiringExactlyNowIsTreatedAsExpired() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(balanceWithOnHand(BigDecimal.TEN)));
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("8"), BigDecimal.ZERO, fixedClock.instant())));

        // isAfter(now) is false for an exactly-equal instant, so this hold is
        // treated as already expired — the full 10 is available, not 2.
        StockReservation result = reservationService.reserve(
                variant, location, BigDecimal.TEN, StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null);

        assertThat(result.getQty()).isEqualByComparingTo("10");
    }

    @Test
    void neverStockedPairThrowsInsufficientAvailabilityNotAnNpe() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.reserve(
                variant, location, BigDecimal.ONE, StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null))
                .isInstanceOf(InsufficientAvailabilityException.class)
                .hasMessageContaining("No stock has ever been received");

        verifyNoInteractions(reservationRepository);
    }

    @Test
    void partiallyFulfilledHoldCountsOnlyItsOutstandingQty() {
        when(balanceRepository.lockByVariantIdAndLocationId(variant.getId(), location.getId()))
                .thenReturn(Optional.of(balanceWithOnHand(BigDecimal.TEN)));
        // qty=10, fulfilledQty=6 -> outstanding=4, not 10.
        when(reservationRepository.lockActiveByVariantAndLocation(variant.getId(), location.getId()))
                .thenReturn(List.of(activeHold(new BigDecimal("10"), new BigDecimal("6"), null)));

        StockReservation result = reservationService.reserve(
                variant, location, new BigDecimal("6"), StockReservation.ReservationSource.ONLINE_CART,
                "CUSTOMER_ORDER", UUID.randomUUID(), null);

        assertThat(result.getQty()).isEqualByComparingTo("6");
    }
}
