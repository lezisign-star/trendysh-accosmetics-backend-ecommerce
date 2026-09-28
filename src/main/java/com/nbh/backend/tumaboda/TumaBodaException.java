package com.nbh.backend.tumaboda;

import com.nbh.backend.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * A TumaBoda call failed. {@code retryable} separates "try again later" (network trouble, a 5xx, a timeout)
 * from "this will never work as sent" (a 4xx: bad address, no credit, invalid key) — the booking retry ladder
 * only climbs for the first kind. Surfaces to a caller as 502, since the fault is upstream of this API.
 * {@code httpStatus} is what TumaBoda answered, or 0 when there was no answer (network trouble, a reply that
 * was not JSON).
 */
public class TumaBodaException extends DomainException {

    private final String errorCode;
    private final boolean retryable;
    private final int httpStatus;

    public TumaBodaException(String errorCode, String message, boolean retryable) {
        this(errorCode, message, retryable, 0, null);
    }

    public TumaBodaException(String errorCode, String message, boolean retryable, Throwable cause) {
        this(errorCode, message, retryable, 0, cause);
    }

    public TumaBodaException(String errorCode, String message, boolean retryable, int httpStatus, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        if (cause != null) {
            initCause(cause);
        }
    }

    public String errorCode() { return errorCode; }

    public boolean retryable() { return retryable; }

    public int httpStatus() { return httpStatus; }
}
