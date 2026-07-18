package com.soham.ledger.service;

import com.soham.ledger.domain.Transaction;

/**
 * Outcome of a single transfer attempt. {@code replayed} indicates the
 * transaction row already existed for this idempotency key rather than
 * being created by this call.
 */
public record TransferExecutionResult(Transaction transaction, boolean replayed) {
}
