package com.nbh.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The request was fine — the DEPLOYMENT is missing something it needs (e.g.
 * no branch seeded as the default online-fulfilment branch, a required
 * virtual stock location not seeded). 500, not 422/400: nothing the caller
 * sent was wrong, and reporting it as a client error either hides a real
 * operational problem from whoever can fix it or asks a customer to retry
 * something that will never succeed on its own.
 */
public class ConfigurationException extends DomainException {
    public ConfigurationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
