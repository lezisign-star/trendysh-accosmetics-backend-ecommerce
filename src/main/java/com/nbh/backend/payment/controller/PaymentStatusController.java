package com.nbh.backend.payment.controller;

import com.nbh.backend.payment.dto.AdminPaymentDtos.ClaimRequest;
import com.nbh.backend.payment.dto.PaymentInstructionsDto;
import com.nbh.backend.payment.service.PaymentClaimService;
import com.nbh.backend.payment.service.PaymentInstructionsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public. Powers the customer's pay page: the first load shows the payment
 * details, and the same call is polled until {@code status} leaves PENDING.
 * Authorised by knowing the order number AND its payment reference.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentStatusController {

    private final PaymentInstructionsService instructionsService;
    private final PaymentClaimService claimService;

    public PaymentStatusController(PaymentInstructionsService instructionsService,
                                   PaymentClaimService claimService) {
        this.instructionsService = instructionsService;
        this.claimService = claimService;
    }

    @GetMapping("/pay/{orderNo}")
    public PaymentInstructionsDto instructions(@PathVariable String orderNo,
                                               @RequestParam(value = "ref", required = false) String reference) {
        // A missing ref is the same 404 as a wrong one (the service treats null as invalid).
        return instructionsService.forOrder(orderNo, reference);
    }

    /** "I've paid": recorded for staff to verify against the bank, never confirms the order itself. */
    @PostMapping("/pay/{orderNo}/claim")
    public PaymentInstructionsDto claim(@PathVariable String orderNo,
                                        @RequestParam(value = "ref", required = false) String reference,
                                        @Valid @RequestBody ClaimRequest body) {
        claimService.submit(orderNo, reference, body.transactionCode(), body.amount(), body.paidOn(),
                body.payerName());
        return instructionsService.forOrder(orderNo, reference);
    }
}
