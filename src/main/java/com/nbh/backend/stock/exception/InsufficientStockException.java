package com.nbh.backend.stock.exception;

import com.nbh.backend.common.exception.ConflictException;

/**
 * §4 C7: moved out of {@code StockPostingService} (was a nested static
 * class) — a domain exception living inside a service class forced
 * {@code GlobalExceptionHandler} to import the service just to reference its
 * exception type, an inverted web-layer-depends-on-service-internals
 * dependency. Behaviour unchanged: still maps to 409 CONFLICT, now via the
 * common {@link ConflictException} base instead of its own dedicated handler.
 */
public class InsufficientStockException extends ConflictException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
