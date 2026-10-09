package com.example.customerrewards.dynamodb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DynamoDB table configuration provider.
 * Centralizes table names for use across repository implementations.
 * All values are configurable via application.yml.
 */
@Component
public class DynamoDbTableConfig {

    private final String customerTableName;
    private final String transactionTableName;
    private final String idempotencyLockTableName;

    public DynamoDbTableConfig(
            @Value("${aws.dynamodb.customer-table:customers}") String customerTableName,
            @Value("${aws.dynamodb.transaction-table:reward_transactions}") String transactionTableName,
            @Value("${aws.dynamodb.idempotency-lock-table:reward_idempotency_locks}") String idempotencyLockTableName) {
        this.customerTableName = customerTableName;
        this.transactionTableName = transactionTableName;
        this.idempotencyLockTableName = idempotencyLockTableName;
    }

    public String getCustomerTableName() {
        return customerTableName;
    }

    public String getTransactionTableName() {
        return transactionTableName;
    }

    public String getIdempotencyLockTableName() {
        return idempotencyLockTableName;
    }
}
