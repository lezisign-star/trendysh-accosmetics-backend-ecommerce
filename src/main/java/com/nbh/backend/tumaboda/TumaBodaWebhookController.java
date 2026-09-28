package com.nbh.backend.tumaboda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * TumaBoda's delivery-status webhook. Public, but authenticated by signature (see
 * {@link TumaBodaWebhookVerifier}); a bad or missing signature is a 401 and nothing is read from the body.
 * Once the signature is good the answer is always 200 — even if processing fails — so TumaBoda does not
 * retry-storm us; the reconciliation poll picks up anything a failed webhook missed.
 *
 * <p>The body, as Moments' working parser reads it, is {@code {"id": "<event id>", "event":
 * "delivery.status_updated", "data": {"id": "<delivery id>", "status": "in_transit", "riderId": "..."}}}.
 * Moments never derives a status from the event name; here an event named {@code delivery.delivered} or
 * {@code delivery.cancelled} whose {@code data.status} is missing is read as that status, on the reasoning
 * that the event name says it outright. (The event id is not used to drop duplicates: applying a report
 * twice is harmless, see {@link TumaBodaStatusService}.)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tumaboda/webhook")
public class TumaBodaWebhookController {

    private final TumaBodaWebhookVerifier verifier;
    private final TumaBodaStatusService statuses;
    private final ObjectMapper objectMapper;

    public TumaBodaWebhookController(TumaBodaWebhookVerifier verifier, TumaBodaStatusService statuses,
                                     ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.statuses = statuses;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-Tuma-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        if (!verifier.verify(signature, body)) {
            log.warn("TumaBoda webhook rejected: missing or invalid signature");
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            String type = root.path("event").asText(root.path("type").asText(""));
            String status = data.path("status").asText(null);
            if (status == null || status.isBlank()) {
                status = statusFromEventName(type);
            }
            statuses.apply(data.path("id").asText(null), status, data.path("riderId").asText(null));
        } catch (Exception e) {
            log.error("TumaBoda webhook accepted but could not be processed: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("received", "true"));
    }

    private static String statusFromEventName(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "delivery.delivered" -> "delivered";
            case "delivery.cancelled", "delivery.canceled" -> "cancelled";
            default -> null;
        };
    }
}
