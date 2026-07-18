package com.soham.ledger.service;

import com.soham.ledger.domain.Transaction;
import com.soham.ledger.repository.TransactionRepository;
import com.soham.ledger.web.exception.InsufficientBalanceException;
import com.soham.ledger.web.exception.InvalidTransferException;
import com.soham.ledger.web.exception.TransferConflictException;
import com.soham.ledger.webhook.WebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferExecutor transferExecutor;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WebhookDispatcher webhookDispatcher;

    private TransferService transferService;

    private final UUID fromId = UUID.randomUUID();
    private final UUID toId = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("100.00");
    private final String idempotencyKey = "key-1";

    @BeforeEach
    void setUp() {
        // maxRetries=3, baseBackoffMs=1 so retry tests run fast
        transferService = new TransferService(transferExecutor, transactionRepository, webhookDispatcher, 3, 1);
    }

    @Test
    void successfulTransfer_returnsCompletedNonReplayedResult() {
        Transaction tx = new Transaction(fromId, toId, amount, idempotencyKey);
        tx.markCompleted();
        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenReturn(new TransferExecutionResult(tx, false));

        TransferExecutionResult result = transferService.transfer(fromId, toId, amount, idempotencyKey);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().getStatus().name()).isEqualTo("COMPLETED");
        verify(transferExecutor, times(1)).executeAttempt(fromId, toId, amount, idempotencyKey);
    }

    @Test
    void insufficientBalance_throwsInsufficientBalanceException() {
        Transaction tx = new Transaction(fromId, toId, amount, idempotencyKey);
        tx.markFailed("Account " + fromId + " has insufficient balance: balance=10.00, requested=100.00");
        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenReturn(new TransferExecutionResult(tx, false));

        assertThatThrownBy(() -> transferService.transfer(fromId, toId, amount, idempotencyKey))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("insufficient balance");
    }

    @Test
    void duplicateIdempotencyKey_fastPath_returnsReplayedResultWithoutError() {
        Transaction tx = new Transaction(fromId, toId, amount, idempotencyKey);
        tx.markCompleted();
        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenReturn(new TransferExecutionResult(tx, true));

        TransferExecutionResult result = transferService.transfer(fromId, toId, amount, idempotencyKey);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction().getIdempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    void duplicateIdempotencyKey_concurrentRace_recoversViaLookupAfterConstraintViolation() {
        Transaction winner = new Transaction(fromId, toId, amount, idempotencyKey);
        winner.markCompleted();

        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.of(winner));

        TransferExecutionResult result = transferService.transfer(fromId, toId, amount, idempotencyKey);

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isEqualTo(winner);
        verify(transferExecutor, times(1)).executeAttempt(fromId, toId, amount, idempotencyKey);
    }

    @Test
    void optimisticLockConflict_retriesAndEventuallySucceeds() {
        Transaction tx = new Transaction(fromId, toId, amount, idempotencyKey);
        tx.markCompleted();

        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", fromId))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", fromId))
                .thenReturn(new TransferExecutionResult(tx, false));

        TransferExecutionResult result = transferService.transfer(fromId, toId, amount, idempotencyKey);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction().getStatus().name()).isEqualTo("COMPLETED");
        verify(transferExecutor, times(3)).executeAttempt(fromId, toId, amount, idempotencyKey);
    }

    @Test
    void optimisticLockConflict_exhaustsRetries_throwsConflict() {
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(transferExecutor.executeAttempt(fromId, toId, amount, idempotencyKey))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", fromId));

        assertThatThrownBy(() -> transferService.transfer(fromId, toId, amount, idempotencyKey))
                .isInstanceOf(TransferConflictException.class);

        // initial attempt + 3 retries = 4 calls total (maxRetries = 3)
        verify(transferExecutor, times(4)).executeAttempt(fromId, toId, amount, idempotencyKey);
    }

    @Test
    void selfTransfer_rejectedBeforeHittingExecutor() {
        assertThatThrownBy(() -> transferService.transfer(fromId, fromId, amount, idempotencyKey))
                .isInstanceOf(InvalidTransferException.class);

        verify(transferExecutor, never()).executeAttempt(any(), any(), any(), anyString());
    }

    @Test
    void nonPositiveAmount_rejectedBeforeHittingExecutor() {
        assertThatThrownBy(() -> transferService.transfer(fromId, toId, BigDecimal.ZERO, idempotencyKey))
                .isInstanceOf(InvalidTransferException.class);

        verify(transferExecutor, never()).executeAttempt(any(), any(), any(), anyString());
    }
}
