package com.soham.ledger.web.exception;

/**
 * Thrown for client-input problems: negative/zero amount, self-transfer,
 * currency mismatch. Maps to HTTP 400.
 */
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
