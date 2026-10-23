package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.PriceChangeDtos.Answer;
import com.nbh.backend.order.dto.PriceChangeDtos.PriceChangeView;
import com.nbh.backend.order.dto.PriceChangeDtos.Waiting;
import com.nbh.backend.order.entity.OrderPriceChange.Acknowledgement;
import com.nbh.backend.order.service.OrderPriceChangeService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The price changes staff made while entering orders (owner, 2026-10-23), and the answers of the owner or manager each
 * was agreed with. Looking at them is for the people who oversee the money; answering is for those who may approve a price
 * change. See {@link OrderPriceChangeService}.
 */
@RestController
@RequestMapping("/api/v1/admin/price-changes")
public class PriceChangeController {

    private final OrderPriceChangeService priceChanges;

    public PriceChangeController(OrderPriceChangeService priceChanges) {
        this.priceChanges = priceChanges;
    }

    /** The changes waiting for this person's answer, the longest waiting first: their dashboard note. */
    @PreAuthorize("hasAuthority('PERM_ORDER_PRICE_APPROVE')")
    @GetMapping("/waiting")
    public List<PriceChangeView> waiting(@AuthenticationPrincipal StaffUser me) {
        return priceChanges.waitingFor(me);
    }

    @PreAuthorize("hasAuthority('PERM_ORDER_PRICE_APPROVE')")
    @GetMapping("/waiting/count")
    public Waiting waitingCount(@AuthenticationPrincipal StaffUser me) {
        return new Waiting(priceChanges.waitingFor(me).size());
    }

    /** Every price change in a period (default: the last 30 days), newest first, optionally only with some answers. */
    @PreAuthorize("hasAnyAuthority('PERM_ORDER_PRICE_APPROVE','PERM_FINANCE_VIEW')")
    @GetMapping
    public Page<PriceChangeView> inPeriod(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<Acknowledgement> status,
            @PageableDefault(size = 25) Pageable pageable) {
        Instant end = to == null ? Instant.now().plusSeconds(60) : to;
        Instant start = from == null ? end.minus(java.time.Duration.ofDays(30)) : from;
        Set<Acknowledgement> only = EnumSet.noneOf(Acknowledgement.class);
        if (status != null) {
            only.addAll(status);
        }
        return priceChanges.inPeriod(start, end, only, pageable);
    }

    @PreAuthorize("hasAuthority('PERM_ORDER_PRICE_APPROVE')")
    @PostMapping("/{id}/confirm")
    public PriceChangeView confirm(@PathVariable UUID id, @Valid @RequestBody(required = false) Answer answer,
                                   @AuthenticationPrincipal StaffUser me) {
        return priceChanges.answer(id, true, answer == null ? null : answer.note(), me);
    }

    @PreAuthorize("hasAuthority('PERM_ORDER_PRICE_APPROVE')")
    @PostMapping("/{id}/dispute")
    public PriceChangeView dispute(@PathVariable UUID id, @Valid @RequestBody(required = false) Answer answer,
                                   @AuthenticationPrincipal StaffUser me) {
        return priceChanges.answer(id, false, answer == null ? null : answer.note(), me);
    }
}
