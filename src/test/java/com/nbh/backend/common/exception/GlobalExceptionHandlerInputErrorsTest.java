package com.nbh.backend.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two ways a caller's own mistake used to come back as something that was not a plain 400, both found in the
 * 2026-10-06 go-live QA: checkout without the cart-session header answered 500, and a number too big for its column
 * (an extra few zeros typed into a count or a price) answered "A conflicting record already exists — please retry".
 */
class GlobalExceptionHandlerInputErrorsTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<Object> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unused")
    private void aHandlerWithAHeader(String header) {}

    @Test
    void aMissingRequiredHeaderIsABadRequestThatNamesTheHeader() throws Exception {
        MethodParameter parameter = new MethodParameter(
                getClass().getDeclaredMethod("aHandlerWithAHeader", String.class), 0);
        MissingRequestHeaderException missing = new MissingRequestHeaderException("X-Cart-Session", parameter);

        assertThat(new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class).resolveMethodByThrowable(missing).getName())
                .as("not the catch-all that answers 500").isEqualTo("handleMissingHeader");
        ResponseEntity<Object> response = handler.handleMissingHeader(missing);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(response).get("error").toString()).contains("X-Cart-Session");
    }

    @Test
    void aNumberTooBigForItsColumnIsABadRequestNotAConflict() {
        // Postgres reports "numeric field overflow" as SQLSTATE 22003; Hibernate and Spring wrap it a couple of layers deep.
        SQLException overflow = new SQLException("ERROR: numeric field overflow", "22003");
        DataIntegrityViolationException wrapped = new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("wrapper", overflow));

        ResponseEntity<Object> response = handler.handleDataIntegrityViolation(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(response).get("error").toString()).contains("too large").doesNotContain("conflicting");
    }

    @Test
    void aRealCollisionIsStillAConflictTheCallerCanRetry() {
        SQLException duplicate = new SQLException("ERROR: duplicate key value violates unique constraint", "23505");
        DataIntegrityViolationException wrapped = new DataIntegrityViolationException("could not execute statement", duplicate);

        ResponseEntity<Object> response = handler.handleDataIntegrityViolation(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bodyOf(response).get("error").toString()).contains("conflicting record");
    }

    @Test
    void aDataIntegrityErrorWithNoSqlCauseAtAllIsStillAConflict() {
        ResponseEntity<Object> response = handler.handleDataIntegrityViolation(new DataIntegrityViolationException("odd"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
