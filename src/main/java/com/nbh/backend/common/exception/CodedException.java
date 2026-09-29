package com.nbh.backend.common.exception;

/**
 * An error a client must be able to recognise without parsing its message, e.g. to react to a changed
 * price by refreshing the basket instead of just showing text. The handler adds {@link #code()} to the
 * JSON error body next to the human-readable {@code error}.
 */
public interface CodedException {

    String code();
}
