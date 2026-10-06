package com.example.customerrewards.exception;

public class InvalidRewardPointsException extends RuntimeException {
    public InvalidRewardPointsException(int points) {
        super("Reward points must be greater than zero, but was: " + points);
    }
}
