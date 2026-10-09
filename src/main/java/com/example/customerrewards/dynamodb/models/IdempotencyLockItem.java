package com.example.customerrewards.dynamodb.models;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

/**
 * Idempotency lock item for DynamoDB.
 *
 * Stored in a dedicated reward_idempotency_locks table.
 *
 * Used to ensure exactly one redemption processes for a given (customerId, idempotencyKey).
 *
 * Primary Key Design:
 * - Partition key: lockId (String)
 *   Format: "IDEMPOTENCY#<customerId>#<idempotencyKey>"
 *   This composite PK ensures uniqueness across all concurrent requests
 *
 * Attributes:
 * - lockId: Partition key, deterministic composite ID
 * - transactionId: Reference to the successful redemption transaction (UUID as string)
 * - customerId: Customer ID for reference
 * - createdAt: Timestamp when lock was acquired (ISO-8601 UTC)
 *
 * Semantics:
 * - Created atomically with customer balance update and transaction creation via TransactWriteItems
 * - Condition: attribute_not_exists(lockId) ensures exactly ONE concurrent request succeeds
 * - On retry (same idempotencyKey): client queries this lock item and retrieves original transactionId
 * - On successful query: client can fetch the cached transaction result without re-processing
 *
 * TTL:
 * - Can be configured with DynamoDB TTL (e.g., 24 hours) to clean up old lock items
 * - Not strictly required, but recommended for production cleanup
 */
@DynamoDbBean
public class IdempotencyLockItem {

    private String lockId;
    private String transactionId;
    private String customerId;
    private Instant createdAt;

    // Constructors
    public IdempotencyLockItem() {
    }

    public IdempotencyLockItem(String lockId, String transactionId, String customerId, Instant createdAt) {
        this.lockId = lockId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.createdAt = createdAt;
    }

    // Getters and Setters

    @DynamoDbPartitionKey
    @DynamoDbAttribute("lockId")
    public String getLockId() {
        return lockId;
    }

    public void setLockId(String lockId) {
        this.lockId = lockId;
    }

    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbAttribute("customerId")
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
