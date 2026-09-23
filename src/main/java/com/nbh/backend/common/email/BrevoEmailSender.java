package com.nbh.backend.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends through Brevo's transactional API (the provider this client's other
 * projects use). Active only when {@code BREVO_API_KEY} is set. Untested against
 * the live API — no key was available when this was written; the request shape
 * follows Brevo's documented {@code POST /v3/smtp/email}.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.mail.brevo-api-key:}' != ''")
public class BrevoEmailSender implements EmailSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String apiKey;
    private final String fromEmail;
    private final String fromName;

    public BrevoEmailSender(RestClient.Builder restClientBuilder,
                             @Value("${app.mail.brevo-api-key}") String apiKey,
                             @Value("${app.mail.from-email:}") String fromEmail,
                             @Value("${app.mail.from-name:Trendysh Accosmetic}") String fromName) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        this.restClient = restClientBuilder.baseUrl("https://api.brevo.com").requestFactory(factory).build();
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.fromName = fromName;
    }

    @Override
    public void send(String toEmail, String subject, String textBody) {
        send(toEmail, subject, textBody, null);
    }

    @Override
    public void send(String toEmail, String subject, String textBody, String htmlBody) {
        if (fromEmail.isBlank()) {
            log.error("MAIL_FROM_EMAIL is not set — cannot send \"{}\" to {}", subject, toEmail);
            return;
        }
        try {
            Map<String, Object> body = new java.util.HashMap<>(Map.of(
                    "sender", Map.of("email", fromEmail, "name", fromName),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "textContent", textBody));
            if (htmlBody != null) {
                body.put("htmlContent", htmlBody);
            }
            restClient.post()
                    .uri("/v3/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // Never let a mail outage fail a registration or reset request.
            log.error("Failed to send \"{}\" to {}: {}", subject, toEmail, e.getMessage());
        }
    }
}
