package com.nbh.backend.common.event;

/**
 * A transactional email a customer-account flow wants sent. Published inside the
 * transaction and delivered only after it commits, so nobody is emailed a link
 * for an account or token that then failed to save.
 */
public record CustomerEmailRequestedEvent(String toEmail, String subject, String textBody) {}
