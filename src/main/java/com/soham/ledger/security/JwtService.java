package com.soham.ledger.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMinutes;

    public JwtService(AuthProperties authProperties) {
        this.signingKey = Keys.hmacShaKeyFor(authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = authProperties.jwtExpirationMinutes();
    }

    public String generateToken(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(expirationMinutes))))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the username if the token is valid and unexpired, or empty if not.
     */
    public java.util.Optional<String> validateAndGetUsername(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return java.util.Optional.of(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }
}
