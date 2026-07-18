package com.soham.ledger.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soham.ledger.domain.Transaction;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fires an HMAC-signed "transfer.completed" notification to a configured
 * URL whenever a transfer completes — simulating how a real ledger would
 * notify downstream systems (e.g. a merchant's payment webhook) of a
 * settled transfer. Dispatch is fire-and-forget on a background executor:
 * a slow or unreachable webhook receiver must never slow down or fail the
 * transfer request itself.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookProperties properties;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "webhook-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    public WebhookDispatcher(WebhookProperties properties, WebhookSigner signer, ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    public void dispatchTransferCompleted(Transaction transaction) {
        if (!properties.enabled()) {
            return;
        }

        TransferCompletedEvent event = new TransferCompletedEvent(
                "transfer.completed",
                transaction.getId(),
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getAmount(),
                Instant.now());

        executor.submit(() -> send(event));
    }

    private void send(TransferCompletedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String signature = signer.sign(payload, properties.secret());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Ledger-Signature", "sha256=" + signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Webhook delivered for transaction {}: HTTP {}", event.transactionId(), response.statusCode());
            } else {
                log.warn("Webhook rejected for transaction {}: HTTP {} {}", event.transactionId(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Webhook delivery failed for transaction {}: {}", event.transactionId(), e.toString());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
