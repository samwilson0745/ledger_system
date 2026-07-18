package com.soham.ledger.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request signing, the same scheme used by Stripe/GitHub-style
 * webhooks: the receiver recomputes the signature over the raw body with
 * the shared secret and compares in constant time, so a payload can't be
 * forged or tampered with in transit without the secret.
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] rawSignature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawSignature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to compute webhook signature", e);
        }
    }

    public boolean verify(String payload, String secret, String signatureToVerify) {
        String expected = sign(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureToVerify.getBytes(StandardCharsets.UTF_8));
    }
}
