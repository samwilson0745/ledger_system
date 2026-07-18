package com.soham.ledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Demo credential store, bound from {@code ledger.auth.*}. There is no user
 * table or persistent account system in this project — auth is a stretch
 * goal layered on top of the core ledger, so credentials are a fixed list
 * from config rather than a real identity provider. Passwords are plaintext
 * in application.yml, clearly marked as demo-only; do not reuse this pattern
 * for real user accounts.
 */
@ConfigurationProperties(prefix = "ledger.auth")
public record AuthProperties(String jwtSecret, long jwtExpirationMinutes, List<DemoUser> users) {

    public record DemoUser(String username, String password) {
    }
}
