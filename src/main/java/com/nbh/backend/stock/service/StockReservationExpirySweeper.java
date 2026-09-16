package com.nbh.backend.stock.service;

import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.repository.StockReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Audit finding F9 (session 3): nothing ever flipped a lapsed cart hold to
 * EXPIRED or gave its quantity back to {@code stock_balance.reserved_qty}.
 * {@link StockReservationService#reserve} correctly excludes expired holds
 * from its own availability arithmetic (it checks {@code expiresAt} live), so
 * a checkout could never actually oversell past an expired hold — but
 * anything reading the CACHED {@code stock_balance.reserved_qty}/
 * {@code availableQty} directly (an admin inventory screen, a future
 * storefront "N left in stock" badge) would see a permanently inflated
 * reserved figure and an artificially low availability, long after the
 * customer who held it walked away. Two divergent definitions of "available"
 * in the same system is exactly what the design doc's reservation table was
 * meant to collapse into one.
 *
 * Runs every minute; each expiry goes through the same locked
 * {@link StockReservationService#expire} path as an explicit release, so it
 * carries the same double-decrement protection (audit finding F4).
 */
@Slf4j
@Component
public class StockReservationExpirySweeper {

    private final StockReservationRepository reservationRepository;
    private final StockReservationService reservationService;

    public StockReservationExpirySweeper(StockReservationRepository reservationRepository,
                                          StockReservationService reservationService) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${app.stock.reservation-sweep-interval-ms:60000}")
    public void sweepExpiredReservations() {
        List<StockReservation> lapsed = reservationRepository
                .findByStatusAndExpiresAtBefore(StockReservation.ReservationStatus.ACTIVE, Instant.now());

        for (StockReservation reservation : lapsed) {
            UUID id = reservation.getId();
            try {
                reservationService.expire(id);
            } catch (Exception e) {
                // One bad row must never stop the sweep from processing the rest.
                log.warn("Failed to expire reservation {}: {}", id, e.getMessage());
            }
        }

        if (!lapsed.isEmpty()) {
            log.info("Expired {} lapsed stock reservation(s)", lapsed.size());
        }
    }
}
