package com.nbh.backend.stock.controller;

import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.ReserveStockRequest;
import com.nbh.backend.stock.dto.StockReservationDto;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockReservationService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The anti-oversell hold. Both the eventual online-checkout flow and the
 * eventual POS module call this same endpoint (with source=ONLINE_ORDER /
 * POS_TICKET respectively) — see StockReservationService's class javadoc for
 * why that unification matters. A reservation always holds SHOP stock —
 * branches were removed 2026-09-24 (founder decision — see HANDOFF.md).
 */
@RestController
@RequestMapping("/api/v1/stock/reservations")
public class StockReservationController {

    private final StockReservationService reservationService;
    private final ProductVariantRepository variantRepository;

    public StockReservationController(StockReservationService reservationService,
                                       ProductVariantRepository variantRepository) {
        this.reservationService = reservationService;
        this.variantRepository = variantRepository;
    }

    /** Phase 1 (2026-09-27): the staff-facing "Holds" list — read-only, so PERM_STOCK_VIEW is enough. */
    @PreAuthorize("hasAuthority('PERM_STOCK_VIEW')")
    @GetMapping
    public Page<StockReservationDto> browse(@RequestParam(required = false) StockReservation.ReservationStatus status,
                                             @PageableDefault(size = 20) Pageable pageable) {
        return reservationService.browse(status, pageable).map(StockReservationDto::from);
    }

    // Mutating operations — deliberately NOT gated on STOCK_VIEW (a read-only
    // permission VIEWER holds). A VIEWER account must not be able to lock up
    // inventory or release/fulfill an arbitrary hold by raw UUID. Audit finding
    // F1 (session 3): this was originally PERM_STOCK_VIEW on all three methods.
    @PreAuthorize("hasAnyAuthority('PERM_POS_SELL','PERM_ORDER_MANAGE_ALL','PERM_STOCK_ADJUST')")
    @PostMapping
    @Transactional
    public StockReservationDto reserve(@Valid @RequestBody ReserveStockRequest request,
                                        @AuthenticationPrincipal StaffUser staff) {
        ProductVariant variant = variantRepository.findById(request.variantId())
                .orElseThrow(() -> new EntityNotFoundException("No product variant: " + request.variantId()));

        Instant expiresAt = request.expiresInSeconds() != null
                ? Instant.now().plusSeconds(request.expiresInSeconds())
                : null;

        StockReservation reservation = reservationService.reserve(
                variant, new BigDecimal(request.qty()), request.source(),
                request.referenceType(), request.referenceId(), expiresAt);

        return StockReservationDto.from(reservation);
    }

    @PreAuthorize("hasAnyAuthority('PERM_POS_SELL','PERM_ORDER_MANAGE_ALL','PERM_STOCK_ADJUST')")
    @PostMapping("/{id}/release")
    public void release(@PathVariable UUID id) {
        reservationService.release(id);
    }

    @PreAuthorize("hasAnyAuthority('PERM_POS_SELL','PERM_ORDER_MANAGE_ALL','PERM_STOCK_ADJUST')")
    @PostMapping("/{id}/fulfill")
    public void fulfill(@PathVariable UUID id) {
        reservationService.fulfill(id);
    }
}
