package com.soham.ledger.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stands in for an external payment partner's webhook receiver, so the
 * whole signed-notification round trip can be demonstrated without any
 * real third-party service. A genuine external receiver would perform
 * exactly this check: recompute the HMAC over the raw body with the
 * shared secret and reject anything that doesn't match — not authenticate
 * via JWT, since it's not a caller of this API.
 */
@RestController
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final WebhookProperties properties;
    private final WebhookSigner signer;

    public WebhookReceiverController(WebhookProperties properties, WebhookSigner signer) {
        this.properties = properties;
        this.signer = signer;
    }

    @PostMapping("/webhooks/test-receiver")
    public ResponseEntity<Map<String, String>> receive(@RequestBody String rawBody,
                                                         @RequestHeader(value = "X-Ledger-Signature", required = false) String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            log.warn("Rejected webhook: missing X-Ledger-Signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "missing signature"));
        }

        String providedSignature = signatureHeader.substring(SIGNATURE_PREFIX.length());
        boolean valid = signer.verify(rawBody, properties.secret(), providedSignature);

        if (!valid) {
            log.warn("Rejected webhook: signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "invalid signature"));
        }

        log.info("Verified webhook payload: {}", rawBody);
        return ResponseEntity.ok(Map.of("status", "verified"));
    }
}
