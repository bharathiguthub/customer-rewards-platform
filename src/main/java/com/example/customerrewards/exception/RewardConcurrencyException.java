package com.example.customerrewards.exception;

import java.util.UUID;

public class RewardConcurrencyException extends RuntimeException {
    private final boolean retryable = true;

    public RewardConcurrencyException(UUID customerId, Throwable cause) {
        super("Concurrent modification detected for customer: " + customerId + ". Please retry.", cause);
    }

    public boolean isRetryable() { return retryable; }
}
