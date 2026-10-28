package com.nbh.backend.attention;

import com.nbh.backend.payment.entity.PaymentClaim;
import com.nbh.backend.payment.repository.PaymentClaimRepository;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.stock.service.StockShortfallService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The critical banner every staff page shows (owner, 2026-10-28): payment codes waiting for a check and customers who
 * could not order for stock. Each stays until a person deals with it: a code by approving or rejecting it on Payments,
 * a could-not-order by "Handled". New orders keep their own ring ({@code OrderAlertService}).
 */
@RestController
@RequestMapping("/api/v1/admin/attention/critical")
@PreAuthorize("hasAnyAuthority('PERM_ORDER_VIEW','PERM_ORDER_MANAGE_ALL','PERM_PAYMENT_MANAGE','PERM_STOCK_VIEW','PERM_STOCK_RECEIVE')")
public class CriticalAlertsController {

    private final StockShortfallService shortfalls;
    private final PaymentClaimRepository claims;

    public CriticalAlertsController(StockShortfallService shortfalls, PaymentClaimRepository claims) {
        this.shortfalls = shortfalls;
        this.claims = claims;
    }

    /** {@code paymentClaims} is null for staff who cannot check payments: their banner has no such line. */
    public record Critical(Long paymentClaims, StockShortfallService.Unhandled couldNotOrder) {}

    public record HandleAllRequest(Instant upTo) {}

    @GetMapping
    @Transactional(readOnly = true)
    public Critical critical(@AuthenticationPrincipal StaffUser staff) {
        boolean seesPayments = staff.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("PERM_PAYMENT_MANAGE") || a.getAuthority().equals("PERM_ORDER_MANAGE_ALL"));
        return new Critical(seesPayments ? claims.countByStatus(PaymentClaim.ClaimStatus.PENDING) : null,
                shortfalls.unhandled());
    }

    @PostMapping("/could-not-order/{id}/handled")
    public void handled(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        shortfalls.handle(id, staff.getEmail());
    }

    /** "Mark all handled", offered once the list is open: up to the moment it was read, so a newer one stays. */
    @PostMapping("/could-not-order/handled")
    public int handledAll(@RequestBody(required = false) HandleAllRequest body, @AuthenticationPrincipal StaffUser staff) {
        return shortfalls.handleAll(body == null ? null : body.upTo(), staff.getEmail());
    }

    /** Options shoppers were refused for lately, most refused first (the Restock screen). */
    @GetMapping("/could-not-order/recent")
    public List<StockShortfallService.RefusedOption> recent(@RequestParam(defaultValue = "30") int days) {
        return shortfalls.refusedLately(days);
    }
}
