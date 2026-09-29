package com.nbh.backend.order.controller;

import com.nbh.backend.cart.service.CartService;
import com.nbh.backend.common.exception.PriceChangedException;
import com.nbh.backend.order.dto.CheckoutRequest;
import com.nbh.backend.order.dto.CheckoutResponseDto;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.service.CheckoutService;
import com.nbh.backend.customer.entity.CustomerAccount;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public — guest checkout, scoped by the same X-Cart-Session header CartController uses. */
@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CartService cartService;
    private final com.nbh.backend.payment.service.PaymentInstructionsService instructionsService;

    public CheckoutController(CheckoutService checkoutService,
                              CartService cartService,
                              com.nbh.backend.payment.service.PaymentInstructionsService instructionsService) {
        this.checkoutService = checkoutService;
        this.cartService = cartService;
        this.instructionsService = instructionsService;
    }

    @PostMapping
    @Transactional
    public CheckoutResponseDto checkout(@RequestHeader("X-Cart-Session") String sessionId,
                                         @Valid @RequestBody CheckoutRequest request,
                                         @AuthenticationPrincipal CustomerAccount customer) {
        // What the shopper is charged is what the basket costs NOW: every line is re-priced first, and if
        // the total they were shown no longer matches, nothing is placed (409 PRICE_CHANGED).
        cartService.refreshPricesForCheckout(sessionId, request.expectedSubtotal());

        // Public endpoint: `customer` is null for a guest, or a signed-in shopper's account
        // (the JWT filter still authenticates a Bearer token on permitAll paths).
        CheckoutService.CheckoutResult result = checkoutService.checkout(sessionId, request, customer);

        // A TumaBoda delivery fee is quoted by TumaBoda while the order is being placed, so it can differ
        // from the preview the shopper saw. This whole method is one transaction, so refusing here undoes
        // the order and its stock holds; the shopper sees the new fee and decides again.
        if (request.expectedDeliveryFee() != null
                && request.expectedDeliveryFee().compareTo(result.order().getDeliveryFee()) != 0) {
            throw new PriceChangedException("The delivery price changed while you were checking out."
                    + " Please look over the new total and place your order again.");
        }

        var payment = result.paymentRecord();
        var instructions = payment.getPaymentReference() == null ? null : instructionsService.build(payment);
        return new CheckoutResponseDto(OrderDto.from(result.order()), payment.getId(), instructions);
    }
}
