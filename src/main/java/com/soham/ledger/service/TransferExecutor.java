package com.soham.ledger.service;

import com.soham.ledger.domain.Account;
import com.soham.ledger.domain.Transaction;
import com.soham.ledger.repository.AccountRepository;
import com.soham.ledger.repository.TransactionRepository;
import com.soham.ledger.web.exception.AccountNotFoundException;
import com.soham.ledger.web.exception.InvalidTransferException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Executes exactly one attempt of a transfer inside its own transaction.
 * Kept as a separate bean (rather than a private method on TransferService)
 * so that Spring's transactional proxy is actually invoked on each call —
 * self-invocation would silently skip the proxy and the @Transactional
 * boundary needed for the optimistic-lock retry loop to see a fresh
 * persistence context on every attempt.
 */
@Service
public class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogService auditLogService;

    public TransferExecutor(AccountRepository accountRepository,
                             TransactionRepository transactionRepository,
                             AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransferExecutionResult executeAttempt(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String idempotencyKey) {
        var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new TransferExecutionResult(existing.get(), true);
        }

        // Fetch accounts in a stable order (by id) regardless of debit/credit
        // direction, so two concurrent transfers between the same pair of
        // accounts always acquire row locks in the same order and cannot deadlock.
        UUID firstId = fromAccountId.compareTo(toAccountId) <= 0 ? fromAccountId : toAccountId;
        UUID secondId = firstId.equals(fromAccountId) ? toAccountId : fromAccountId;

        Account first = accountRepository.findById(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));
        Account second = accountRepository.findById(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account fromAccount = first.getId().equals(fromAccountId) ? first : second;
        Account toAccount = first.getId().equals(fromAccountId) ? second : first;

        if (!fromAccount.getCurrency().equals(toAccount.getCurrency())) {
            throw new InvalidTransferException("Currency mismatch: %s vs %s"
                    .formatted(fromAccount.getCurrency(), toAccount.getCurrency()));
        }

        Transaction transaction = new Transaction(fromAccountId, toAccountId, amount, idempotencyKey);

        if (fromAccount.getBalance().compareTo(amount) < 0) {
            transaction.markFailed("Account %s has insufficient balance: balance=%s, requested=%s"
                    .formatted(fromAccountId, fromAccount.getBalance(), amount));
            transactionRepository.save(transaction);
            return new TransferExecutionResult(transaction, false);
        }

        BigDecimal fromBefore = fromAccount.getBalance();
        BigDecimal toBefore = toAccount.getBalance();

        fromAccount.setBalance(fromBefore.subtract(amount));
        toAccount.setBalance(toBefore.add(amount));

        // Force the flush now so any optimistic-lock version conflict
        // surfaces here, inside this attempt, rather than at some later
        // unrelated flush point.
        accountRepository.flush();

        transaction.markCompleted();
        transactionRepository.save(transaction);

        auditLogService.recordBalanceChange(fromAccount.getId(), "DEBIT", fromBefore, fromAccount.getBalance());
        auditLogService.recordBalanceChange(toAccount.getId(), "CREDIT", toBefore, toAccount.getBalance());

        return new TransferExecutionResult(transaction, false);
    }
}
