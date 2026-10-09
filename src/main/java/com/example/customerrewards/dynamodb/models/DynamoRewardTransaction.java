package com.example.customerrewards.dynamodb.models;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

/**
 * DynamoDB persistence model for RewardTransaction.
 *
 * Mapped using AWS SDK v2 Enhanced Client annotations.
 *
 * Primary Key Design:
 * - Partition key: transactionId (UUID as String)
 * - No sort key (single-item read pattern; transactionId is globally unique)
 *
 * GSI Design:
 * - GSI1: Partition key = customerId, Sort key = createdAt
 *   Purpose: Query "all transactions for a customer, sorted by creation time (newest first)"
 *   Used by: findByCustomerOrderByCreatedAtDesc()
 *   Query pattern: GSI1-PK = :customerId, sorted by GSI1-SK DESC
 *
 * Attributes:
 * - transactionId: Partition key, unique transaction identifier (UUID as string)
 * - createdAt: Regular attribute, transaction timestamp (ISO-8601 UTC); also GSI1-SK for ordering
 * - customerId: GSI1-PK, identifies transaction owner
 * - transactionType: REDEEM, EARN, etc.
 * - points: Points involved in transaction
 * - idempotencyKey: Stored for reference (null for EARN, present for REDEEM)
 * - remainingBalanceSnapshot: Customer balance immediately after this transaction
 *
 * Idempotency Design (Phase 5 fix):
 * - Idempotency enforcement via a separate lock item with composite PK:
 *   PK = "IDEMPOTENCY#<customerId>#<idempotencyKey>"
 * - Lock item created atomically with transaction and customer update via TransactWriteItems
 * - Condition: attribute_not_exists(idempotencyId) ensures only ONE concurrent request succeeds
 * - Lock item stores transactionId for idempotent result retrieval on retry
 */
@DynamoDbBean
public class DynamoRewardTransaction {

    private String transactionId;
    private Instant createdAt;
    private String customerId;
    private String transactionType;
    private int points;
    private String idempotencyKey;
    private Integer remainingBalanceSnapshot;

    // Constructors
    public DynamoRewardTransaction() {
    }

    public DynamoRewardTransaction(String transactionId, Instant createdAt, String customerId,
                                    String transactionType, int points, String idempotencyKey,
                                    Integer remainingBalanceSnapshot) {
        this.transactionId = transactionId;
        this.createdAt = createdAt;
        this.customerId = customerId;
        this.transactionType = transactionType;
        this.points = points;
        this.idempotencyKey = idempotencyKey;
        this.remainingBalanceSnapshot = remainingBalanceSnapshot;
    }

    // Getters and Setters

    @DynamoDbPartitionKey
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbAttribute("createdAt")
    @DynamoDbSecondarySortKey(indexNames = "GSI1")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("customerId")
    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @DynamoDbAttribute("transactionType")
    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    @DynamoDbAttribute("points")
    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    @DynamoDbAttribute("idempotencyKey")
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    @DynamoDbAttribute("remainingBalanceSnapshot")
    public Integer getRemainingBalanceSnapshot() {
        return remainingBalanceSnapshot;
    }

    public void setRemainingBalanceSnapshot(Integer remainingBalanceSnapshot) {
        this.remainingBalanceSnapshot = remainingBalanceSnapshot;
    }
}
