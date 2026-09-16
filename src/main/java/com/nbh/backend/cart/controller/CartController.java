package com.nbh.backend.cart.controller;

import com.nbh.backend.cart.dto.AddToCartRequest;
import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.dto.UpdateCartItemRequest;
import com.nbh.backend.cart.service.CartService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Public, unauthenticated — cart access is scoped entirely by the
 * X-Cart-Session header a guest's browser generates and persists (e.g. in
 * localStorage). If the header is missing, a fresh session id is minted and
 * echoed back so the frontend can start persisting it from that point on.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private static final String SESSION_HEADER = "X-Cart-Session";

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartDto get(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                        HttpServletResponse response) {
        String resolved = resolveSession(sessionId, response);
        return cartService.getOrCreateCart(resolved);
    }

    @PostMapping("/items")
    public CartDto addItem(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                            @Valid @RequestBody AddToCartRequest request,
                            HttpServletResponse response) {
        String resolved = resolveSession(sessionId, response);
        return cartService.addItem(resolved, request.variantId(), request.qty());
    }

    @PutMapping("/items/{itemId}")
    public CartDto updateItem(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                               @PathVariable UUID itemId,
                               @Valid @RequestBody UpdateCartItemRequest request,
                               HttpServletResponse response) {
        String resolved = resolveSession(sessionId, response);
        return cartService.updateItemQty(resolved, itemId, request.qty());
    }

    @DeleteMapping("/items/{itemId}")
    public CartDto removeItem(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                               @PathVariable UUID itemId,
                               HttpServletResponse response) {
        String resolved = resolveSession(sessionId, response);
        return cartService.removeItem(resolved, itemId);
    }

    private String resolveSession(String sessionId, HttpServletResponse response) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        String fresh = UUID.randomUUID().toString();
        response.setHeader(SESSION_HEADER, fresh);
        return fresh;
    }
}
