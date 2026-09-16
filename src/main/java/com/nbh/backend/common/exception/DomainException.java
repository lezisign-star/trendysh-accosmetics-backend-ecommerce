package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * §4 C7: a small exception taxonomy, replacing {@code IllegalStateException}/
 * {@code IllegalArgumentException} used as catch-alls for genuinely different
 * situations (a client sending bad input, a referenced entity missing, a
 * valid request hitting the wrong state, a server misconfiguration — all
 * previously mapped through the same one or two generic exception types,
 * which is how a server misconfiguration ended up reported to a customer as
 * a 422 they could do nothing about). Each subtype below carries its own
 * correct {@link HttpStatus} so {@code GlobalExceptionHandler} needs exactly
 * one handler for all of them, instead of importing service classes to
 * reference their nested exception types (the inverted web-layer-depends-on-
 * service-internals dependency this refactor also removes).
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;

    protected DomainException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
