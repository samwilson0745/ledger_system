package com.soham.ledger.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger.webhook")
public record WebhookProperties(boolean enabled, String url, String secret) {
}
