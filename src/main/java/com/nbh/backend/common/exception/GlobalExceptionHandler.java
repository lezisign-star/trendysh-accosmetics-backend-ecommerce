package com.nbh.backend.common.exception;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §4 C7 refactor. Three changes from the pre-refactor version:
 *
 * <ol>
 *   <li>{@code InsufficientStockException}/{@code InsufficientAvailabilityException}
 *   no longer need their own handlers or a service-class import — both now
 *   extend {@link DomainException} (via {@link ConflictException}) and are
 *   caught by {@link #handleDomainException} along with every other domain
 *   exception. This removes the inverted dependency where the web layer
 *   imported {@code StockPostingService}/{@code StockReservationService} just
 *   to reference their nested exception types.</li>
 *   <li>{@link #handleUnexpected} is a new true fallback — previously an
 *   unmapped exception fell through to Boot's default error handling with no
 *   log line at all, which meant a genuine 500 could go completely unnoticed.
 *   Every handler here now logs, and this one logs at ERROR with a
 *   correlation id in both the log line and the response body, so a reported
 *   error can be found in the logs without needing a stack trace pasted into
 *   a support ticket.</li>
 *   <li>{@link #handleDataIntegrityViolation} now logs the real exception
 *   (previously swallowed entirely) — the actual violated constraint name
 *   never reached a log from here before, which made diagnosing a real
 *   collision (as opposed to the idempotency-key races handled transparently
 *   inside {@code StockPostingService.post}) harder than it needed to be.</li>
 * </ol>
 *
 * {@code IllegalStateException}/{@code IllegalArgumentException} stay mapped
 * (422/400) as a safety net for call sites not yet migrated to the typed
 * taxonomy — see each type's own handler for why neither should be the
 * *first* choice for new domain-error code.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(EntityNotFoundException e) {
        log.warn("404: {}", e.getMessage());
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * A request against a URL no controller maps (found live, 2026-09-25: a stale Railway
     * deployment made every request to two brand-new endpoints hit this path instead of their
     * real handlers — but the bug was here regardless, since it fell through to
     * {@link #handleUnexpected} and returned a 500 for what is really just "not found").
     * Spring's own default for this is already 404; this handler only exists so the response
     * body matches this API's own JSON error shape instead of Boot's default HTML/JSON page.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("404: no mapping for {} {}", e.getHttpMethod(), e.getResourcePath());
        return body(HttpStatus.NOT_FOUND, "No such endpoint: " + e.getResourcePath());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Object> handleBadCredentials(BadCredentialsException e) {
        log.warn("401: {}", e.getMessage());
        return body(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** Permission-authority violations surface here as a normal 403 JSON body. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(org.springframework.security.access.AccessDeniedException e) {
        log.warn("403: {}", e.getMessage());
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Every exception in the §4 C7 taxonomy — {@link NotFoundException},
     * {@link ConflictException} (including {@code InsufficientStockException}/
     * {@code InsufficientAvailabilityException}), {@link UnprocessableException},
     * and {@link ConfigurationException} — carries its own correct status, so
     * one handler covers all of them instead of one per concrete type.
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Object> handleDomainException(DomainException e) {
        if (e.getStatus().is5xxServerError()) {
            log.error("{}: {}", e.getStatus().value(), e.getMessage(), e);
        } else {
            log.warn("{}: {}", e.getStatus().value(), e.getMessage());
        }
        ResponseEntity<Object> response = body(e.getStatus(), e.getMessage());
        if (e instanceof CodedException coded && response.getBody() instanceof Map<?, ?> payload) {
            @SuppressWarnings("unchecked")
            Map<String, Object> editable = (Map<String, Object>) payload;
            editable.put("code", coded.code());
        }
        return response;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleIllegalState(IllegalStateException e) {
        log.warn("422: {}", e.getMessage());
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("400: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A malformed numeric string in a request body (audit finding F12, session 3) — 400, not a raw 500. */
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Object> handleNumberFormat(NumberFormatException e) {
        log.warn("400: invalid numeric value: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Invalid numeric value: " + e.getMessage());
    }

    /**
     * A genuine DB-level collision that slipped past application checks — e.g. a
     * document_no collision unrelated to idempotency (audit finding F5/F10,
     * session 3). The idempotency-key race itself is handled transparently
     * inside StockPostingService.post(); this is the backstop for everything
     * else, so a real collision is a 409 a caller can retry, not a raw 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        // A number too big for its column (typing an extra few zeros into a stock count or a price) is the typist's
        // mistake and can be fixed on the spot, not "a conflicting record": Postgres reports it as SQLSTATE 22003.
        if (NestedExceptionUtils.getMostSpecificCause(e) instanceof java.sql.SQLException sql
                && "22003".equals(sql.getSQLState())) {
            log.warn("400: a number was too large for its column: {}", sql.getMessage());
            return body(HttpStatus.BAD_REQUEST, "One of the numbers is too large — check it and try again");
        }
        log.error("409: unhandled data integrity violation", e);
        return body(HttpStatus.CONFLICT, "A conflicting record already exists — please retry");
    }

    /**
     * Phase 0 fix (2026-09-27): covers both kinds of lock failure the stock/reservation
     * correctness work can now throw — an optimistic {@code @Version} mismatch (the new
     * backstop on {@code StockBalance}/{@code StockReservation}, which turns a stale read
     * into a loud failure instead of a silent lost update) and a pessimistic lock
     * timeout/deadlock (two transactions genuinely contending for the same row — e.g. a
     * checkout racing a dispatch). Both are exactly the case a caller should retry, not
     * treat as permanent; previously both fell through to the 500 catch-all with nothing
     * telling the caller a retry might succeed.
     */
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<Object> handleConcurrencyFailure(org.springframework.dao.ConcurrencyFailureException e) {
        log.warn("409: concurrency failure, caller should retry: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "This was just updated by someone else — please retry");
    }

    /**
     * A malformed or semantically-invalid request body — e.g. a JSON value
     * that isn't one of the enum's remaining constants (found live: after
     * V12 removed DELIVERY_TUMABODA from FulfilmentType, sending that value
     * to /checkout produced a raw 500 instead of a 400, because it's a
     * subtype of Exception and nothing more specific was registered for it
     * — the new catch-all below was silently swallowing it). Spring's own
     * default handling would map this correctly on its own if this
     * @RestControllerAdvice didn't also register a bare Exception.class
     * handler; adding the specific case here is the minimal fix without
     * touching the broader fallback's behaviour for genuinely unexpected
     * exceptions.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleMalformedRequestBody(HttpMessageNotReadableException e) {
        log.warn("400: malformed request body: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Malformed or invalid request body");
    }

    /** Same shadowing risk as above — a bad UUID/enum in a @PathVariable or @RequestParam (e.g. GET /orders/not-a-uuid). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("400: type mismatch on '{}': {}", e.getName(), e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    /** Same shadowing risk — a required @RequestParam left out (e.g. GET /stock/balance with no variantId) is the caller's mistake: 400, not a 500. */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("400: missing parameter '{}'", e.getParameterName());
        return body(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'");
    }

    /** Same shadowing risk — a required header left out (e.g. checkout without the X-Cart-Session header) is the caller's mistake: 400, not a 500. */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Object> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e) {
        log.warn("400: missing header '{}'", e.getHeaderName());
        return body(HttpStatus.BAD_REQUEST, "Missing required header '" + e.getHeaderName() + "'");
    }

    /** Same shadowing risk — e.g. a GET against a POST-only endpoint. Spring's own default is 405; keep it that way. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("405: {}", e.getMessage());
        return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
        log.warn("400: validation failed: {}", fieldErrors);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", HttpStatus.BAD_REQUEST.value());
        payload.put("error", "Validation failed");
        payload.put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }

    /**
     * A browser that went away mid-response: tab closed, page left, signal lost. It is how every live-update
     * stream ends sooner or later (the server finds out on its next write to it), so it is neither an error to
     * alarm anyone with nor a response to build — there is nobody left to send one to. With no handler of its
     * own it fell into {@link #handleUnexpected}: a full ERROR stack trace and a 500 in the access log and
     * metrics for every visitor who simply closed a tab, which would bury the genuine 500s this class exists
     * to surface.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientGone(Exception e) {
        log.debug("The client went away before the response finished: {}", e.getMessage());
    }

    /**
     * True fallback — anything not mapped above previously fell through to
     * Boot's default error handling with no application log line at all, so a
     * genuine 500 could go completely unnoticed. The correlation id appears in
     * both the log line (searchable) and the response body (quotable by
     * whoever reports the error), without leaking the exception's message or
     * stack trace to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        log.error("500 [{}]: unhandled exception", correlationId, e);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        payload.put("error", "An unexpected error occurred");
        payload.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(payload);
    }

    private ResponseEntity<Object> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("status", status.value());
        payload.put("error", message);
        return ResponseEntity.status(status).body(payload);
    }
}
