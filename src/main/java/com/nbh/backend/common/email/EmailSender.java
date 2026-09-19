package com.nbh.backend.common.email;

/**
 * Outbound transactional email. Implementations must never throw into the
 * caller: an email that can't be delivered is logged, and the customer can ask
 * for it again (verification / reset links are re-sendable).
 */
public interface EmailSender {

    void send(String toEmail, String subject, String textBody);
}
