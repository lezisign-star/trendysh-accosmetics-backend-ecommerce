package com.nbh.backend.stock.controller;

import com.nbh.backend.branch.entity.StockLocation;
import com.nbh.backend.branch.repository.StockLocationRepository;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.stock.dto.ReserveStockRequest;
import com.nbh.backend.stock.dto.StockReservationDto;
import com.nbh.backend.stock.entity.StockReservation;
import com.nbh.backend.stock.service.StockReservationService;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.service.StaffBranchAccessService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
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
 * why that unification matters.
 */
@RestController
@RequestMapping("/api/v1/stock/reservations")
public class StockReservationController {

    private final StockReservationService reservationService;
    private final ProductVariantRepository variantRepository;
    private final StockLocationRepository locationRepository;
    private final StaffBranchAccessService branchAccessService;

    public StockReservationController(StockReservationService reservationService,
                                       ProductVariantRepository variantRepository,
                                       StockLocationRepository locationRepository,
                                       StaffBranchAccessService branchAccessService) {
        this.reservationService = reservationService;
        this.variantRepository = variantRepository;
        this.locationRepository = locationRepository;
        this.branchAccessService = branchAccessService;
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
        StockLocation location = locationRepository.findById(request.locationId())
                .orElseThrow(() -> new EntityNotFoundException("No stock location: " + request.locationId()));
        if (location.getBranch() != null) {
            branchAccessService.assertCanAccessBranch(staff, location.getBranch().getId());
        }

        Instant expiresAt = request.expiresInSeconds() != null
                ? Instant.now().plusSeconds(request.expiresInSeconds())
                : null;

        StockReservation reservation = reservationService.reserve(
                variant, location, new BigDecimal(request.qty()), request.source(),
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
