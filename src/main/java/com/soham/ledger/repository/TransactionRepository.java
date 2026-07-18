package com.soham.ledger.repository;

import com.soham.ledger.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    @Query("select coalesce(sum(t.amount), 0) from Transaction t " +
            "where t.fromAccountId = :accountId and t.status = com.soham.ledger.domain.TransactionStatus.COMPLETED")
    BigDecimal sumCompletedOutgoing(@Param("accountId") UUID accountId);

    @Query("select coalesce(sum(t.amount), 0) from Transaction t " +
            "where t.toAccountId = :accountId and t.status = com.soham.ledger.domain.TransactionStatus.COMPLETED")
    BigDecimal sumCompletedIncoming(@Param("accountId") UUID accountId);
}
