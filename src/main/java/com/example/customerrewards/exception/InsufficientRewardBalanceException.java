package com.example.customerrewards.exception;

public class InsufficientRewardBalanceException extends RuntimeException {
    private final int requestedPoints;
    private final int currentBalance;

    public InsufficientRewardBalanceException(int requestedPoints, int currentBalance) {
        super("Insufficient reward balance. Requested: " + requestedPoints + ", available: " + currentBalance);
        this.requestedPoints = requestedPoints;
        this.currentBalance = currentBalance;
    }

    public int getRequestedPoints() { return requestedPoints; }
    public int getCurrentBalance() { return currentBalance; }
}
