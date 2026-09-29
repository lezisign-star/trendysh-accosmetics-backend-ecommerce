package com.nbh.backend.common.exception;

/**
 * What a customer was shown is not what checkout would now charge: a price moved (a deal ended, an admin
 * edited it) or TumaBoda quoted a different delivery fee since they last looked. Nothing was placed; the
 * client refreshes its basket and total and the customer places the order again knowingly. 409, code
 * {@value #CODE}.
 */
public class PriceChangedException extends ConflictException implements CodedException {

    public static final String CODE = "PRICE_CHANGED";

    public PriceChangedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return CODE;
    }
}
