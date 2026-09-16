package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/** A well-formed, valid request that cannot be applied given current state (e.g. checking out an empty cart) — 422. */
public class UnprocessableException extends DomainException {
    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
