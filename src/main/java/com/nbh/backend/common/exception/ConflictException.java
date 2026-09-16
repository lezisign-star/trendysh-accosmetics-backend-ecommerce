package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/** A genuine conflict with current state (e.g. insufficient stock/availability) — 409, a caller can retry or adjust. */
public class ConflictException extends DomainException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
