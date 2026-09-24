package com.nbh.backend.payment.coop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives Co-op Bank INS deposit notifications. NOT tested against the real bank (no credentials yet): built
 * from the bank's public INSSimulation specification. The bank authenticates with a shared Basic credential only,
 * so this endpoint never confirms an order unless {@code app.coop.ins.auto-match} is explicitly turned on.
 * Unconfigured credentials reject everything.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments/coop")
public class CoopInsController {

    private final CoopInsService service;
    private final CoopInsSettings settings;

    public CoopInsController(CoopInsService service, CoopInsSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    @PostMapping("/ins")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) CoopInsNotification notification) {

        if (!authentic(authorization)) {
            log.warn("Co-op INS rejected: missing or wrong credentials");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        service.handle(notification);
        // Acknowledge anything we could parse, including ignored events: a 200 stops the bank retrying.
        Map<String, String> ack = new LinkedHashMap<>();
        ack.put("MessageCode", "0");
        ack.put("MessageDescription", "Success");
        if (notification != null && notification.messageReference() != null) {
            ack.put("MessageReference", notification.messageReference());
        }
        return ResponseEntity.ok(ack);
    }

    /** A body we cannot read is logged and acknowledged, like the M-Pesa callback: retrying will not fix it. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        log.warn("Co-op INS: unreadable body: {}", e.getMessage());
        return ResponseEntity.ok(Map.of("MessageCode", "0", "MessageDescription", "Success"));
    }

    private boolean authentic(String authorization) {
        if (!settings.configured() || authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String expected = settings.username() + ":" + settings.password();
        byte[] supplied;
        try {
            supplied = Base64.getDecoder().decode(authorization.substring(6).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(supplied, expected.getBytes(StandardCharsets.UTF_8));
    }
}
