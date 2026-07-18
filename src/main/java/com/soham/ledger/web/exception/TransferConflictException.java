package com.soham.ledger.web.exception;

/**
 * Thrown when the bounded optimistic-lock retry budget is exhausted under
 * heavy write contention. Maps to HTTP 409 — the client should retry.
 */
public class TransferConflictException extends RuntimeException {

    public TransferConflictException(String message) {
        super(message);
    }
}
