package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.models.IdempotencyLockItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository interface for RewardTransaction persistence.
 *
 * Mirrors the JPA RewardTransactionRepository interface.
 * Methods are to be implemented using AWS SDK v2 Enhanced Client
 * with GSI queries for customer transactions and idempotency lookups.
 */
public interface DynamoRewardTransactionRepository {

    /**
     * Find transaction by partition key (transactionId).
     */
    Optional<DynamoRewardTransaction> findById(String transactionId);

    /**
     * Find all transactions for a customer, sorted by createdAt descending.
     * Requires GSI1: customerId (PK), createdAt (SK).
     */
    List<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId);

    /**
     * Find all transactions for a customer with pagination.
     * Requires GSI1: customerId (PK), createdAt (SK).
     */
    Page<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId, Pageable pageable);

    /**
     * Retrieve idempotency lock item by compositeIdempotencyId.
     * Composite ID format: IDEMPOTENCY#<customerId>#<idempotencyKey>
     * Returns the lock item if the idempotent request was already processed.
     * Used by client to retrieve cached result on retry.
     */
    Optional<IdempotencyLockItem> getIdempotencyLock(String compositeIdempotencyId);

    /**
     * Save or insert transaction.
     */
    DynamoRewardTransaction save(DynamoRewardTransaction transaction);

    /**
     * Execute atomic redemption using transactWriteItems.
     * Atomically updates customer balance and creates transaction record.
     * Both operations succeed or both fail.
     *
     * @param customerId The customer ID
     * @param expectedCustomerVersion The expected customer version for optimistic locking
     * @param pointsToRedeem Points to deduct from balance
     * @param idempotencyKey Unique key for idempotency (detects duplicate REDEEM requests)
     * @param transaction The DynamoRewardTransaction to insert
     * @throws DynamoDbException if transaction fails (wrapped with condition failure details)
     */
    void executeRedemptionTransaction(String customerId, long expectedCustomerVersion, 
                                      int pointsToRedeem, String idempotencyKey,
                                      DynamoRewardTransaction transaction);
}
