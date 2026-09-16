package com.nbh.backend.stock.exception;

import com.nbh.backend.common.exception.ConflictException;

/**
 * §4 C7: moved out of {@code StockReservationService} (was a nested static
 * class) — see {@link InsufficientStockException}'s javadoc for why.
 * Behaviour unchanged: still maps to 409 CONFLICT.
 */
public class InsufficientAvailabilityException extends ConflictException {
    public InsufficientAvailabilityException(String message) {
        super(message);
    }
}
