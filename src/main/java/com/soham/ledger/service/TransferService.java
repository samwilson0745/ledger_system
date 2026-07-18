package com.soham.ledger.service;

import com.soham.ledger.domain.Transaction;
import com.soham.ledger.domain.TransactionStatus;
import com.soham.ledger.repository.TransactionRepository;
import com.soham.ledger.web.exception.InsufficientBalanceException;
import com.soham.ledger.web.exception.InvalidTransferException;
import com.soham.ledger.web.exception.TransactionNotFoundException;
import com.soham.ledger.web.exception.TransferConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Public entry point for transfers. Owns input validation and the bounded
 * optimistic-lock retry loop; delegates each individual attempt to
 * {@link TransferExecutor}, which runs it in its own fresh transaction.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferExecutor transferExecutor;
    private final TransactionRepository transactionRepository;
    private final int maxRetries;
    private final long baseBackoffMs;

    public TransferService(TransferExecutor transferExecutor,
                            TransactionRepository transactionRepository,
                            @Value("${ledger.transfer.max-retries:3}") int maxRetries,
                            @Value("${ledger.transfer.retry-base-backoff-ms:20}") long baseBackoffMs) {
        this.transferExecutor = transferExecutor;
        this.transactionRepository = transactionRepository;
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
    }

    public TransferExecutionResult transfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String idempotencyKey) {
        validate(fromAccountId, toAccountId, amount, idempotencyKey);

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                TransferExecutionResult result = transferExecutor.executeAttempt(fromAccountId, toAccountId, amount, idempotencyKey);
                if (result.transaction().getStatus() == TransactionStatus.FAILED) {
                    throw new InsufficientBalanceException(result.transaction().getFailureReason());
                }
                return result;
            } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
                var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                if (existing.isPresent()) {
                    Transaction tx = existing.get();
                    if (tx.getStatus() == TransactionStatus.FAILED) {
                        throw new InsufficientBalanceException(tx.getFailureReason());
                    }
                    return new TransferExecutionResult(tx, true);
                }

                if (attempt > maxRetries) {
                    log.warn("Transfer {} -> {} exhausted {} retries under contention", fromAccountId, toAccountId, maxRetries);
                    throw new TransferConflictException(
                            "Transfer could not complete after %d retries due to concurrent writes on the same account"
                                    .formatted(maxRetries));
                }

                sleepWithBackoff(attempt);
            }
        }
    }

    public Transaction getTransaction(UUID transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private void validate(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String idempotencyKey) {
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException("Cannot transfer to the same account");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Transfer amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException("idempotencyKey is required");
        }
    }

    private void sleepWithBackoff(int attempt) {
        int cappedExponent = Math.min(attempt - 1, 4);
        long backoff = baseBackoffMs * (1L << cappedExponent);
        long jitter = ThreadLocalRandom.current().nextLong(baseBackoffMs);
        try {
            Thread.sleep(backoff + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransferConflictException("Interrupted while retrying transfer");
        }
    }
}
