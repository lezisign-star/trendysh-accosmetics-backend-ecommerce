package com.nbh.backend.common.email;

/**
 * Outbound transactional email. Implementations must never throw into the
 * caller: an email that can't be delivered is logged, and the customer can ask
 * for it again (verification / reset links are re-sendable).
 */
public interface EmailSender {

    void send(String toEmail, String subject, String textBody);

    /**
     * Same as {@link #send(String, String, String)}, with an HTML alternative for clients that
     * render it (the plain-text version stays the fallback everywhere else — spam filters, plain-
     * text clients, the dev-only logging sender). Default falls back to text-only, so callers that
     * don't build an HTML body — and implementations, like the logging sender, that don't need one —
     * aren't forced to touch this.
     */
    default void send(String toEmail, String subject, String textBody, String htmlBody) {
        send(toEmail, subject, textBody);
    }
}
