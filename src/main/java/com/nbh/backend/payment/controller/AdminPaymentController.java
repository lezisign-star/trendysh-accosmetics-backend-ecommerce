package com.nbh.backend.payment.controller;

import com.nbh.backend.payment.dto.AdminPaymentDtos.AllocateRequest;
import com.nbh.backend.payment.dto.AdminPaymentDtos.AwaitingPaymentDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.ChaseNoteRequest;
import com.nbh.backend.payment.dto.AdminPaymentDtos.ClaimDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.CreditDto;
import com.nbh.backend.payment.dto.AdminPaymentDtos.RecordCreditRequest;
import com.nbh.backend.payment.dto.AdminPaymentDtos.ResolveRequest;
import com.nbh.backend.payment.service.AdminPaymentService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff payments screen: bank orders waiting for money, customers' "I've paid"
 * claims, and credits seen in the bank account. Gated like confirm-manual: these
 * decide whether an order is treated as paid.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
@PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
public class AdminPaymentController {

    private final AdminPaymentService service;

    public AdminPaymentController(AdminPaymentService service) {
        this.service = service;
    }

    @GetMapping("/awaiting")
    public List<AwaitingPaymentDto> awaiting() {
        return service.awaiting();
    }

    @GetMapping("/claims")
    public List<ClaimDto> claims() {
        return service.pendingClaims();
    }

    @PostMapping("/claims/{id}/approve")
    public ClaimDto approve(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff,
                            @Valid @RequestBody(required = false) ResolveRequest body) {
        return service.approveClaim(id, staff.getEmail(), body == null ? null : body.note(),
                body == null ? null : body.fullCode());
    }

    @PostMapping("/{paymentId}/chase-note")
    public void chaseNote(@PathVariable UUID paymentId, @AuthenticationPrincipal StaffUser staff,
                          @Valid @RequestBody ChaseNoteRequest body) {
        service.saveChaseNote(paymentId, staff.getEmail(), body.note());
    }

    @PostMapping("/claims/{id}/reject")
    public ClaimDto reject(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff,
                           @Valid @RequestBody ResolveRequest body) {
        return service.rejectClaim(id, staff.getEmail(), body.note());
    }

    @GetMapping("/credits")
    public List<CreditDto> credits() {
        return service.unmatchedCredits();
    }

    @PostMapping("/credits")
    public CreditDto recordCredit(@Valid @RequestBody RecordCreditRequest body) {
        return service.recordCredit(body);
    }

    @PostMapping("/credits/{id}/allocate")
    public CreditDto allocate(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff,
                              @Valid @RequestBody AllocateRequest body) {
        return service.allocateCredit(id, body.orderNo(), staff.getEmail(), body.note());
    }

    @PostMapping("/credits/{id}/ignore")
    public CreditDto ignore(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff,
                            @Valid @RequestBody ResolveRequest body) {
        return service.ignoreCredit(id, staff.getEmail(), body.note());
    }
}
