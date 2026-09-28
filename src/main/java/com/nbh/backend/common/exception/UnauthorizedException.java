package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/** The caller has not proven who they are, or their proof has run out (an expired lookup session) — 401. */
public class UnauthorizedException extends DomainException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
