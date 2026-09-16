package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/** A referenced entity does not exist — 404. */
public class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
