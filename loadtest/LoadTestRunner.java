import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone concurrency + correctness proof for the ledger system.
 *
 * Runs entirely outside the Spring Boot app, over real HTTP, against a
 * running instance. Creates two accounts, fires a large number of
 * concurrent transfer requests between them (deliberately reusing some
 * idempotency keys), then calls /reconcile and prints a copy-pasteable
 * summary.
 *
 * Usage:
 *   java LoadTestRunner.java [baseUrl] [requestCount] [concurrency]
 *
 * Defaults: baseUrl=http://localhost:8080, requestCount=1000, concurrency=50
 *
 * Requires no external dependencies — single-file source execution
 * (JEP 330), Java 21+: `java LoadTestRunner.java`.
 */
public class LoadTestRunner {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        int requestCount = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        System.out.println("=== Ledger Concurrency Load Test ===");
        System.out.println("Target:        " + baseUrl);
        System.out.println("Requests:      " + requestCount);
        System.out.println("Concurrency:   " + concurrency);
        System.out.println();

        BigDecimal startingBalance = new BigDecimal("1000000.00");
        String accountAId = createAccount(baseUrl, "Load-Test-Alice", "USD", startingBalance);
        String accountBId = createAccount(baseUrl, "Load-Test-Bob", "USD", startingBalance);
        System.out.println("Created account A: " + accountAId + " (starting balance " + startingBalance + ")");
        System.out.println("Created account B: " + accountBId + " (starting balance " + startingBalance + ")");
        System.out.println();

        List<PlannedTransfer> originals = new ArrayList<>();
        List<PlannedTransfer> plan = new ArrayList<>();
        int duplicateEveryNth = 11; // ~9% of requests intentionally reuse an earlier idempotency key
        String runId = String.valueOf(System.currentTimeMillis());

        for (int i = 0; i < requestCount; i++) {
            if (i % duplicateEveryNth == 0 && !originals.isEmpty()) {
                plan.add(originals.get(i % originals.size()));
            } else {
                boolean aToB = (i % 2 == 0);
                String from = aToB ? accountAId : accountBId;
                String to = aToB ? accountBId : accountAId;
                String amount = String.valueOf(1 + (i % 250));
                PlannedTransfer t = new PlannedTransfer(from, to, amount, "loadtest-" + runId + "-" + i);
                plan.add(t);
                originals.add(t);
            }
        }

        int duplicateRequestCount = plan.size() - originals.size();
        System.out.println("Distinct transfers planned:  " + originals.size());
        System.out.println("Intentional duplicate calls: " + duplicateRequestCount + " (reusing an earlier idempotencyKey)");
        System.out.println();

        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        AtomicInteger unprocessable422 = new AtomicInteger();
        AtomicInteger otherErrors = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(plan.size());
        List<String> sampleErrors = new CopyOnWriteArrayList<>();

        Instant start = Instant.now();

        for (PlannedTransfer t : plan) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    HttpResponse<String> response = postTransfer(baseUrl, t);
                    switch (response.statusCode()) {
                        case 201 -> created.incrementAndGet();
                        case 200 -> replayed.incrementAndGet();
                        case 409 -> conflict409.incrementAndGet();
                        case 422 -> unprocessable422.incrementAndGet();
                        default -> {
                            otherErrors.incrementAndGet();
                            if (sampleErrors.size() < 5) {
                                sampleErrors.add("HTTP " + response.statusCode() + ": " + response.body());
                            }
                        }
                    }
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                    if (sampleErrors.size() < 5) {
                        sampleErrors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.MINUTES);
        executor.shutdown();
        Duration elapsed = Duration.between(start, Instant.now());

        if (!finished) {
            System.out.println("WARNING: not all requests completed within timeout");
        }

        String accountAFinal = getBalance(baseUrl, accountAId);
        String accountBFinal = getBalance(baseUrl, accountBId);
        String reconcileBody = getReconcile(baseUrl);

        System.out.println("=== Results ===");
        System.out.println("Total requests sent:      " + plan.size());
        System.out.println("201 Created (new transfer): " + created.get());
        System.out.println("200 OK (idempotent replay):  " + replayed.get());
        System.out.println("409 Conflict (lock retries exhausted): " + conflict409.get());
        System.out.println("422 Unprocessable (insufficient balance): " + unprocessable422.get());
        System.out.println("Other errors:              " + otherErrors.get());
        if (!sampleErrors.isEmpty()) {
            System.out.println("Sample errors: " + sampleErrors);
        }
        System.out.println("Total time taken:         " + elapsed.toMillis() + " ms");
        System.out.println("Throughput:                " + String.format("%.1f", plan.size() / Math.max(1.0, elapsed.toMillis() / 1000.0)) + " req/s");
        System.out.println();
        System.out.println("Final balance, account A: " + accountAFinal);
        System.out.println("Final balance, account B: " + accountBFinal);
        System.out.println();
        System.out.println("=== Reconciliation ===");
        System.out.println(reconcileBody);

        boolean reconciliationPassed = reconcileBody.contains("\"passed\":true");
        System.out.println();
        if (reconciliationPassed) {
            System.out.println("RESULT: PASS — zero balance discrepancy after " + plan.size() + " concurrent requests ("
                    + duplicateRequestCount + " intentional idempotency-key duplicates correctly deduplicated).");
        } else {
            System.out.println("RESULT: FAIL — reconciliation reported a discrepancy. See details above.");
            System.exit(1);
        }
    }

    private record PlannedTransfer(String fromAccountId, String toAccountId, String amount, String idempotencyKey) {
    }

    private static String createAccount(String baseUrl, String owner, String currency, BigDecimal startingBalance) throws IOException, InterruptedException {
        String json = """
                {"ownerName":"%s","currency":"%s","startingBalance":%s}
                """.formatted(owner, currency, startingBalance.toPlainString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/accounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to create account: HTTP " + response.statusCode() + " " + response.body());
        }
        return extractField(response.body(), "id");
    }

    private static HttpResponse<String> postTransfer(String baseUrl, PlannedTransfer t) throws IOException, InterruptedException {
        String json = """
                {"fromAccountId":"%s","toAccountId":"%s","amount":%s,"idempotencyKey":"%s"}
                """.formatted(t.fromAccountId(), t.toAccountId(), t.amount(), t.idempotencyKey());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/transfers"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String getBalance(String baseUrl, String accountId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/accounts/" + accountId))
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return extractField(response.body(), "balance");
    }

    private static String getReconcile(String baseUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/reconcile"))
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String extractField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^,\"}]+)\"?").matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        throw new RuntimeException("Field '" + field + "' not found in response: " + json);
    }
}
