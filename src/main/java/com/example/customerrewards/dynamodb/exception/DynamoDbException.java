package com.example.customerrewards.dynamodb.exception;

/**
 * DynamoDB-specific exception that wraps AWS SDK errors.
 * Used to bridge DynamoDB API exceptions to domain-level exceptions.
 */
public class DynamoDbException extends RuntimeException {

    public DynamoDbException(String message) {
        super(message);
    }

    public DynamoDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
