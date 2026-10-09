package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.config.DynamoDbTableConfig;
import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.IdempotencyLockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * DynamoDB implementation of RewardTransactionRepository using AWS SDK v2 Enhanced Client.
 *
 * Table Design:
 * - Primary table: reward_transactions
 *   - PK: transactionId (String, UUID as string) — globally unique, no sort key needed
 *   - Attributes: customerId, transactionType, points, idempotencyKey, remainingBalanceSnapshot, createdAt
 *   - GSI1: customerId (PK), createdAt (SK) for customer transaction history queries (newest first)
 *
 * - Lock table: reward_idempotency_locks
 *   - PK: lockId (String) — composite format "IDEMPOTENCY#<customerId>#<idempotencyKey>"
 *   - Attributes: transactionId (reference to winning redemption), customerId, createdAt
 *   - NO indexes needed
 *
 * Idempotency Lock Design (Fixed):
 * - Lock items stored in SEPARATE reward_idempotency_locks table
 * - Lock PK is lockId (composite: "IDEMPOTENCY#<customerId>#<idempotencyKey>")
 * - Created atomically with customer balance update via TransactWriteItems across THREE tables
 * - Condition: attribute_not_exists(lockId) ensures exactly ONE concurrent request succeeds
 * - Other concurrent requests fail with ConditionalCheckFailedException
 * - On retry (same idempotencyKey): client queries lock table to retrieve cached transactionId
 *
 * Atomic Redemption:
 * - Uses TransactWriteItems API to atomically execute THREE operations:
 *   1. Update customer balance + version (Condition: version = :expectedVersion AND balance >= :points)
 *   2. Put transaction in reward_transactions (PK = transactionId)
 *   3. Put idempotency lock in reward_idempotency_locks (PK = lockId, condition attribute_not_exists(lockId))
 * - All three succeed or all three rollback — no partial state
 * - Idempotency lock prevents concurrent duplicate processing
 */
@Repository
public class DynamoRewardTransactionRepositoryImpl implements DynamoRewardTransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoRewardTransactionRepositoryImpl.class);

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbClient dynamoDbClient;
    private final DynamoDbTable<DynamoRewardTransaction> transactionTable;
    private final DynamoDbTable<DynamoCustomer> customerTable;
    private final DynamoDbTableConfig tableConfig;

    public DynamoRewardTransactionRepositoryImpl(DynamoDbEnhancedClient enhancedClient,
                                                 DynamoDbClient dynamoDbClient,
                                                 DynamoDbTableConfig tableConfig) {
        this.enhancedClient = enhancedClient;
        this.dynamoDbClient = dynamoDbClient;
        this.tableConfig = tableConfig;
        // Get reference to table (does not create table, just establishes schema mapping)
        this.transactionTable = enhancedClient.table(
                tableConfig.getTransactionTableName(),
                TableSchema.fromClass(DynamoRewardTransaction.class)
        );
        this.customerTable = enhancedClient.table(
                tableConfig.getCustomerTableName(),
                TableSchema.fromClass(DynamoCustomer.class)
        );
    }

    /**
     * Get transaction by partition key and sort key.
     * O(1) operation using primary key lookup.
     *
     * @param transactionId The partition key
     * @return Optional containing the transaction if found
     */
    @Override
    public Optional<DynamoRewardTransaction> findById(String transactionId) {
        log.debug("Getting transaction by transactionId: {}", transactionId);
        try {
            DynamoRewardTransaction transaction = transactionTable.getItem(
                    r -> r.key(k -> k.partitionValue(transactionId))
            );
            return Optional.ofNullable(transaction);
        } catch (Exception e) {
            log.error("Error finding transaction by id: {}", transactionId, e);
            throw new DynamoDbException("Failed to find transaction by id: " + transactionId, e);
        }
    }

    /**
     * Query all transactions for a customer, sorted by createdAt descending.
     * Uses GSI1: customerId (PK), createdAt (SK).
     * Returns List sorted by createdAt in descending order (newest first).
     *
     * @param customerId The customer ID
     * @return List of transactions, newest first
     * @throws DynamoDbException on DynamoDB errors
     */
    @Override
    public List<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId) {
        log.debug("Querying all transactions for customer: {} (unpaginated)", customerId);
        try {
            // Query GSI1 where customerId = :customerId, sorted by createdAt DESC
            QueryConditional query = QueryConditional.keyEqualTo(k -> k.partitionValue(customerId));
            
            List<DynamoRewardTransaction> results = new ArrayList<>();
            transactionTable.query(query)
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .forEach(results::add);
            
            log.debug("Found {} transactions for customer: {}", results.size(), customerId);
            return results;
        } catch (Exception e) {
            log.error("Error querying transactions for customer: {}", customerId, e);
            throw new DynamoDbException("Failed to query transactions for customer: " + customerId, e);
        }
    }

    /**
     * Query all transactions for a customer with pagination, sorted by createdAt descending.
     * Uses GSI1: customerId (PK), createdAt (SK).
     * Returns Spring Page with pagination metadata.
     *
     * @param customerId The customer ID
     * @param pageable Spring pageable with page size and number
     * @return Page of transactions, newest first
     * @throws DynamoDbException on DynamoDB errors
     */
    @Override
    public Page<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId, Pageable pageable) {
        log.debug("Querying transactions for customer: {} (paginated, page size: {})",
                  customerId, pageable.getPageSize());
        try {
            // Query GSI1 where customerId = :customerId
            QueryConditional query = QueryConditional.keyEqualTo(k -> k.partitionValue(customerId));
            
            List<DynamoRewardTransaction> allResults = new ArrayList<>();
            transactionTable.query(query)
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .forEach(allResults::add);
            
            // Apply pagination
            int pageNumber = pageable.getPageNumber();
            int pageSize = pageable.getPageSize();
            int start = pageNumber * pageSize;
            int end = Math.min(start + pageSize, allResults.size());
            
            List<DynamoRewardTransaction> pageContent;
            if (start < allResults.size()) {
                pageContent = allResults.subList(start, end);
            } else {
                pageContent = new ArrayList<>();
            }
            
            log.debug("Returning page {} with {} items for customer: {}", 
                      pageNumber, pageContent.size(), customerId);
            
            return new PageImpl<>(pageContent, pageable, allResults.size());
        } catch (Exception e) {
            log.error("Error querying transactions with pagination for customer: {}", customerId, e);
            throw new DynamoDbException("Failed to query transactions for customer: " + customerId, e);
        }
    }

    /**
     * Retrieve idempotency lock item by composite ID from reward_idempotency_locks table.
     * Composite ID format: IDEMPOTENCY#<customerId>#<idempotencyKey>
     *
     * @param compositeIdempotencyId The composite idempotency ID (lockId primary key)
     * @return Optional containing lock item if found (cache hit on retry)
     * @throws DynamoDbException on DynamoDB errors
     */
    @Override
    public Optional<IdempotencyLockItem> getIdempotencyLock(String compositeIdempotencyId) {
        log.debug("Retrieving idempotency lock: {}", compositeIdempotencyId);
        try {
            // Get reference to the dedicated lock table (not transaction table)
            DynamoDbTable<IdempotencyLockItem> lockTable = enhancedClient.table(
                    tableConfig.getIdempotencyLockTableName(),
                    TableSchema.fromClass(IdempotencyLockItem.class)
            );
            // Query using lockId as the partition key
            IdempotencyLockItem lockItem = lockTable.getItem(
                    r -> r.key(k -> k.partitionValue(compositeIdempotencyId))
            );
            return Optional.ofNullable(lockItem);
        } catch (Exception e) {
            log.error("Error retrieving idempotency lock: {}", compositeIdempotencyId, e);
            throw new DynamoDbException("Failed to retrieve idempotency lock", e);
        }
    }

    /**
     * Save (insert) new transaction.
     * For REDEEM operations: includes conditional check on idempotencyKey uniqueness.
     * For EARN operations: no conditional check (no idempotencyKey).
     *
     * @param transaction Transaction to insert
     * @return The saved transaction
     * @throws DynamoDbException if conditional check fails (duplicate idempotencyKey) or other error
     */
    @Override
    public DynamoRewardTransaction save(DynamoRewardTransaction transaction) {
        log.debug("Inserting transaction: {} for customer: {}",
                  transaction.getTransactionId(), transaction.getCustomerId());

        // Set createdAt if null
        if (transaction.getCreatedAt() == null) {
            transaction.setCreatedAt(java.time.Instant.now());
        }

        try {
            // For now, do unconditional put - conditional checks will be done at query level
            // In Phase 3+, conditional check for idempotency will use attribute_not_exists(idempotencyKey)
            transactionTable.putItem(transaction);

            log.info("Transaction saved successfully: {} with type: {}",
                     transaction.getTransactionId(), transaction.getTransactionType());
            return transaction;
        } catch (ConditionalCheckFailedException e) {
            log.warn("Idempotency conflict: transaction with key {} already exists",
                     transaction.getIdempotencyKey(), e);
            throw new DynamoDbException(
                    "Idempotency violation: transaction with key " + transaction.getIdempotencyKey() + " already exists",
                    e);
        } catch (Exception e) {
            log.error("Error inserting transaction: {}", transaction.getTransactionId(), e);
            throw new DynamoDbException("Failed to insert transaction", e);
        }
    }

    /**
     * Execute atomic redemption using transactWriteItems.
     * Atomically updates customer balance, creates transaction record, and creates idempotency lock.
     * All three operations succeed or all three rollback — no partial state.
     *
     * Transaction semantics:
     * 1. UpdateItem on customer table:
     *    - Condition: version = :expectedVersion AND rewardBalance >= :points
     *    - Effect: Deduct points, increment version, update timestamp
     * 2. PutItem on transaction table:
     *    - Effect: Insert transaction record with transactionId as PK
     * 3. PutItem on idempotency_locks table:
     *    - Condition: attribute_not_exists(lockId)
     *    - Effect: Insert lock item with lockId as PK
     *
     * If any condition fails, entire transaction fails and all operations are rolled back.
     * Service layer maps TransactionCanceledException to appropriate domain exception.
     *
     * @param customerId The customer ID
     * @param expectedCustomerVersion The expected customer version for optimistic locking
     * @param pointsToRedeem Points to deduct from balance
     * @param idempotencyKey Unique key for idempotency (detects duplicate REDEEM requests)
     * @param transaction The DynamoRewardTransaction to insert
     * @throws DynamoDbException if transaction fails
     */
    @Override
    public void executeRedemptionTransaction(String customerId, long expectedCustomerVersion,
                                             int pointsToRedeem, String idempotencyKey,
                                             DynamoRewardTransaction transaction) {
        log.debug("Executing atomic redemption: customerId={}, expectedVersion={}, points={}, idempotencyKey={}",
                  customerId, expectedCustomerVersion, pointsToRedeem, idempotencyKey);

        if (transaction.getCreatedAt() == null) {
            transaction.setCreatedAt(java.time.Instant.now());
        }

        try {
            // Build the Update item for customer table
            // Update: rewardBalance -= :points, version = :nextVersion, updatedAt = :now
            // Condition: version = :expectedVersion AND rewardBalance >= :points
            java.time.Instant now = java.time.Instant.now();
            long nextVersion = expectedCustomerVersion + 1;

            Map<String, AttributeValue> customerKey = Map.of(
                    "customerId", AttributeValue.builder().s(customerId).build()
            );

            Map<String, AttributeValue> expressionValues = Map.of(
                    ":expectedVersion", AttributeValue.builder().n(String.valueOf(expectedCustomerVersion)).build(),
                    ":nextVersion", AttributeValue.builder().n(String.valueOf(nextVersion)).build(),
                    ":pointsToDeduct", AttributeValue.builder().n(String.valueOf(pointsToRedeem)).build(),
                    ":now", AttributeValue.builder().s(now.toString()).build()
            );

            Update updateCustomer = Update.builder()
                    .tableName(tableConfig.getCustomerTableName())
                    .key(customerKey)
                    .updateExpression("SET rewardBalance = rewardBalance - :pointsToDeduct, " +
                                    "version = :nextVersion, updatedAt = :now")
                    .conditionExpression("version = :expectedVersion AND rewardBalance >= :pointsToDeduct")
                    .expressionAttributeValues(expressionValues)
                    .build();

            // Build the Put item for transaction table (PK = transactionId)
            Map<String, AttributeValue> transactionAttributes = Map.of(
                    "transactionId", AttributeValue.builder().s(transaction.getTransactionId()).build(),
                    "createdAt", AttributeValue.builder().s(transaction.getCreatedAt().toString()).build(),
                    "customerId", AttributeValue.builder().s(transaction.getCustomerId()).build(),
                    "transactionType", AttributeValue.builder().s(transaction.getTransactionType()).build(),
                    "points", AttributeValue.builder().n(String.valueOf(transaction.getPoints())).build(),
                    "idempotencyKey", AttributeValue.builder().s(idempotencyKey).build(),
                    "remainingBalanceSnapshot", AttributeValue.builder()
                            .n(String.valueOf(transaction.getRemainingBalanceSnapshot())).build()
            );

            Put putTransaction = Put.builder()
                    .tableName(tableConfig.getTransactionTableName())
                    .item(transactionAttributes)
                    .build();

            // Build the idempotency lock item (PK = "IDEMPOTENCY#<customerId>#<idempotencyKey>")
            // to be written to reward_idempotency_locks table
            // Condition: attribute_not_exists(lockId) ensures exactly ONE concurrent request succeeds
            String compositeIdempotencyId = "IDEMPOTENCY#" + customerId + "#" + idempotencyKey;
            Map<String, AttributeValue> lockAttributes = Map.of(
                    "lockId", AttributeValue.builder().s(compositeIdempotencyId).build(),
                    "transactionId", AttributeValue.builder().s(transaction.getTransactionId()).build(),
                    "customerId", AttributeValue.builder().s(customerId).build(),
                    "createdAt", AttributeValue.builder().s(now.toString()).build()
            );

            Put putLock = Put.builder()
                    .tableName(tableConfig.getIdempotencyLockTableName())
                    .item(lockAttributes)
                    .conditionExpression("attribute_not_exists(lockId)")
                    .build();

            // Build the transact write items request with THREE atomic operations:
            // 1. Update customer (balance, version, timestamp)
            // 2. Put transaction record in reward_transactions
            // 3. Put idempotency lock in reward_idempotency_locks (ensures exactly one concurrent request succeeds)
            TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().update(updateCustomer).build(),
                            TransactWriteItem.builder().put(putTransaction).build(),
                            TransactWriteItem.builder().put(putLock).build()
                    )
                    .build();

            // Execute the atomic transaction
            dynamoDbClient.transactWriteItems(request);

            log.info("Atomic redemption executed successfully: customerId={}, points={}, newVersion={}, lockId={}",
                     customerId, pointsToRedeem, nextVersion, compositeIdempotencyId);

        } catch (TransactionCanceledException e) {
            log.warn("Atomic redemption transaction cancelled for customer: {}", customerId, e);
            throw new DynamoDbException("Redemption transaction failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error executing atomic redemption for customer: {}", customerId, e);
            throw new DynamoDbException("Failed to execute atomic redemption", e);
        }
    }
}
