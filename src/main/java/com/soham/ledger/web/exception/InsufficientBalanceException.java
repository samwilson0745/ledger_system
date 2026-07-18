package com.soham.ledger.web.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a from-account cannot cover a transfer. Maps to HTTP 422.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super("Account %s has insufficient balance: balance=%s, requested=%s"
                .formatted(accountId, balance, requested));
    }

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
