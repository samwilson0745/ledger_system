package com.soham.ledger.web.dto;

import com.soham.ledger.domain.Transaction;
import com.soham.ledger.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        TransactionStatus status,
        String idempotencyKey,
        boolean replayed,
        Instant createdAt,
        Instant completedAt
) {
    public static TransferResponse from(Transaction tx, boolean replayed) {
        return new TransferResponse(
                tx.getId(),
                tx.getFromAccountId(),
                tx.getToAccountId(),
                tx.getAmount(),
                tx.getStatus(),
                tx.getIdempotencyKey(),
                replayed,
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
