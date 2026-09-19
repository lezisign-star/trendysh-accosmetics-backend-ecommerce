package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/** The caller is retrying too fast (failed logins, repeated emails) — 429, they can try again later. */
public class TooManyRequestsException extends DomainException {
    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
