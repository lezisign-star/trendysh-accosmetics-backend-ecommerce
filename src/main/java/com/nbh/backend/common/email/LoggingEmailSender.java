package com.nbh.backend.common.email;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Used when no email provider is configured (no {@code BREVO_API_KEY}): the
 * message — including any verification/reset link — is written to the log so
 * the flows can be exercised locally. That is a development convenience only;
 * in a real deployment this would put one-time links in the logs, hence the
 * loud warning at startup.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.mail.brevo-api-key:}' == ''")
public class LoggingEmailSender implements EmailSender {

    @PostConstruct
    void warn() {
        log.warn("No email provider configured (BREVO_API_KEY unset): emails are only LOGGED, not sent. "
                + "Set BREVO_API_KEY and MAIL_FROM_EMAIL before any real deployment.");
    }

    @Override
    public void send(String toEmail, String subject, String textBody) {
        log.info("EMAIL (not sent) to={} subject=\"{}\"\n{}", toEmail, subject, textBody);
    }
}
