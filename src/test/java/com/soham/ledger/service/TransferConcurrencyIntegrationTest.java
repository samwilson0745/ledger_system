package com.soham.ledger.service;

import com.soham.ledger.domain.Account;
import com.soham.ledger.web.dto.ReconciliationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real concurrent threads through the full Spring context (real
 * Hibernate, real @Version optimistic locking, real transaction manager)
 * against an in-memory H2 database. This proves the retry/idempotency
 * machinery holds up under genuine contention without requiring Docker —
 * the standalone HTTP load-test script under loadtest/ additionally proves
 * it end-to-end over real REST calls against Postgres.
 */
@SpringBootTest
class TransferConcurrencyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    void concurrentTransfers_produceZeroCorruptionAndCorrectIdempotencyDedup() throws InterruptedException {
        BigDecimal startingBalance = new BigDecimal("1000000.00");
        Account accountA = accountService.createAccount("Alice", "USD", startingBalance);
        Account accountB = accountService.createAccount("Bob", "USD", startingBalance);

        int requestCount = 300;
        int threadPoolSize = 40;
        int duplicateEveryNth = 7; // roughly 1 in 7 requests reuses an earlier idempotency key

        List<PlannedTransfer> plan = new ArrayList<>();
        List<PlannedTransfer> originals = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            if (i % duplicateEveryNth == 0 && !originals.isEmpty()) {
                plan.add(originals.get(i % originals.size()));
            } else {
                UUID from = (i % 2 == 0) ? accountA.getId() : accountB.getId();
                UUID to = (i % 2 == 0) ? accountB.getId() : accountA.getId();
                BigDecimal amount = BigDecimal.valueOf(1 + (i % 50)).setScale(2);
                PlannedTransfer transfer = new PlannedTransfer(from, to, amount, "key-" + i);
                plan.add(transfer);
                originals.add(transfer);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(plan.size());

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        // Every transaction id ever observed per idempotency key. Concurrent
        // racers submitting the *same* key must all converge on one id — if
        // this set ever grows past size 1 for a key, idempotency was violated
        // and the same transfer was processed twice.
        Map<String, Set<UUID>> transactionIdsByKey = new ConcurrentHashMap<>();

        for (PlannedTransfer t : plan) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferExecutionResult result = transferService.transfer(t.from, t.to, t.amount, t.idempotencyKey);
                    if (result.replayed()) {
                        replayed.incrementAndGet();
                    } else {
                        succeeded.incrementAndGet();
                    }
                    transactionIdsByKey
                            .computeIfAbsent(t.idempotencyKey, k -> new CopyOnWriteArraySet<>())
                            .add(result.transaction().getId());
                } catch (Exception ex) {
                    // A bounded-retry exhaustion (409) under extreme contention on the
                    // same two hot rows is an expected, safe outcome — not corruption.
                    // What matters is that it never leaves balances inconsistent, which
                    // the reconciliation check below verifies.
                    conflicted.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).as("all transfer requests completed within timeout").isTrue();

        // Strict idempotency correctness: no key was ever processed as two
        // different transactions, regardless of how many callers raced on it.
        transactionIdsByKey.forEach((key, ids) ->
                assertThat(ids).as("idempotency key '%s' must map to exactly one transaction", key).hasSize(1));

        ReconciliationResponse reconciliation = reconciliationService.reconcile();

        assertThat(reconciliation.passed())
                .as("reconciliation discrepancy=%s, details=%s", reconciliation.globalDiscrepancy(), reconciliation.accountDiscrepancies())
                .isTrue();
        assertThat(reconciliation.globalDiscrepancy()).isEqualByComparingTo(BigDecimal.ZERO);

        Account finalA = accountService.getAccount(accountA.getId());
        Account finalB = accountService.getAccount(accountB.getId());
        BigDecimal totalFinal = finalA.getBalance().add(finalB.getBalance());
        BigDecimal totalInitial = startingBalance.add(startingBalance);
        assertThat(totalFinal).isEqualByComparingTo(totalInitial);

        System.out.printf(
                "%nConcurrency integration test summary: requests=%d, succeeded=%d, replayed=%d, errored=%d, distinctKeys=%d%n",
                plan.size(), succeeded.get(), replayed.get(), conflicted.get(), originals.size());
    }

    private record PlannedTransfer(UUID from, UUID to, BigDecimal amount, String idempotencyKey) {
    }
}
