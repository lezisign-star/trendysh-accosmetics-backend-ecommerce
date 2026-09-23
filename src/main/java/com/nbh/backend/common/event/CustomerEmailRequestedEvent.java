package com.nbh.backend.common.event;

/**
 * A transactional email a customer-account flow wants sent. Published inside the
 * transaction and delivered only after it commits, so nobody is emailed a link
 * for an account or token that then failed to save.
 *
 * {@code htmlBody} is optional (null for the emails that don't have a designed HTML version yet) —
 * {@link CustomerEmailListener} falls back to a plain-text send when it's absent.
 */
public record CustomerEmailRequestedEvent(String toEmail, String subject, String textBody, String htmlBody) {

    /** Plain-text-only convenience constructor, for emails without a designed HTML version yet. */
    public CustomerEmailRequestedEvent(String toEmail, String subject, String textBody) {
        this(toEmail, subject, textBody, null);
    }
}
