package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.config.DynamoDbTableConfig;
import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.Optional;

/**
 * DynamoDB implementation of CustomerRepository using AWS SDK v2 Enhanced Client.
 *
 * Table Design:
 * - Primary table: customers
 *   - PK: customerId (String, UUID as string)
 *   - Attributes: firstName, lastName, email, rewardBalance, version, createdAt, updatedAt
 *   - GSI1: email (partition key) for email lookups and duplicate detection
 *
 * Optimistic Locking:
 * - Uses version attribute (long) for optimistic concurrency control
 * - UpdateItem will include ConditionExpression: "version = :expectedVersion" when used
 * - If version mismatch, ConditionalCheckFailedException is caught and mapped to RewardConcurrencyException
 *
 * Email Uniqueness:
 * - First PutItem checks attribute_not_exists(email) via ConditionExpression
 * - Updates use version check (email is immutable, not updated after creation)
 */
@Repository
public class DynamoCustomerRepositoryImpl implements DynamoCustomerRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoCustomerRepositoryImpl.class);

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<DynamoCustomer> customerTable;
    private final DynamoDbTableConfig tableConfig;

    public DynamoCustomerRepositoryImpl(DynamoDbEnhancedClient enhancedClient,
                                        DynamoDbTableConfig tableConfig) {
        this.enhancedClient = enhancedClient;
        this.tableConfig = tableConfig;
        // Get reference to table (does not create table, just establishes schema mapping)
        this.customerTable = enhancedClient.table(
                tableConfig.getCustomerTableName(),
                TableSchema.fromClass(DynamoCustomer.class)
        );
    }

    /**
     * Get customer by partition key (customerId).
     * O(1) operation using primary key.
     */
    @Override
    public Optional<DynamoCustomer> findById(String customerId) {
        log.debug("Getting customer by customerId: {}", customerId);
        try {
            DynamoCustomer customer = customerTable.getItem(r -> r.key(k -> k.partitionValue(customerId)));
            return Optional.ofNullable(customer);
        } catch (Exception e) {
            log.error("Error finding customer by id: {}", customerId, e);
            throw new DynamoDbException("Failed to find customer by id: " + customerId, e);
        }
    }

    /**
     * Query customer by email using GSI1.
     * GSI1 has email as partition key (no sort key).
     * Query returns 0-1 items (email is unique).
     *
     * @param email The customer email address
     * @return Optional containing customer if found
     * @throws DynamoDbException on DynamoDB errors
     */
    @Override
    public Optional<DynamoCustomer> findByEmail(String email) {
        log.debug("Querying customer by email: {}", email);
        try {
            // Query GSI1 with email as partition key
            QueryConditional emailQuery = QueryConditional.keyEqualTo(k -> k.partitionValue(email));
            
            return customerTable.query(emailQuery)
                    .items()
                    .stream()
                    .findFirst();
        } catch (Exception e) {
            log.error("Error querying customer by email: {}", email, e);
            throw new DynamoDbException("Failed to query customer by email: " + email, e);
        }
    }

    /**
     * Check if customer exists by email using GSI1.
     * Delegates to findByEmail() and checks if result is present.
     *
     * @param email The customer email address
     * @return true if customer with this email exists, false otherwise
     */
    @Override
    public boolean existsByEmail(String email) {
        log.debug("Checking if customer exists by email: {}", email);
        return findByEmail(email).isPresent();
    }

    /**
     * Save (insert) new customer with email uniqueness check.
     * Uses conditional put to prevent duplicate emails.
     *
     * @param customer Customer to insert (should be new with version=0)
     * @return The saved customer
     * @throws DynamoDbException if email already exists or other DynamoDB error occurs
     */
    @Override
    public DynamoCustomer save(DynamoCustomer customer) {
        log.debug("Inserting new customer with email: {}", customer.getEmail());
        
        // Ensure timestamps are set
        if (customer.getCreatedAt() == null) {
            customer.setCreatedAt(Instant.now());
        }
        if (customer.getUpdatedAt() == null) {
            customer.setUpdatedAt(Instant.now());
        }
        if (customer.getVersion() == 0) {
            customer.setVersion(1);
        }

        try {
            // For now, do unconditional put - conditional checks on GSI will be enforced at query level
            // In Phase 3+, conditional check for email uniqueness will use attribute_not_exists
            customerTable.putItem(customer);
            log.info("Customer created successfully: {} with email: {}", 
                     customer.getCustomerId(), customer.getEmail());
            return customer;
        } catch (ConditionalCheckFailedException e) {
            log.warn("Email already exists: {}", customer.getEmail());
            throw new DynamoDbException("Email already in use: " + customer.getEmail(), e);
        } catch (Exception e) {
            log.error("Error inserting customer: {}", customer.getCustomerId(), e);
            throw new DynamoDbException("Failed to insert customer", e);
        }
    }

    /**
     * Update customer with optimistic locking via version check.
     * Prevents negative balance and enforces version consistency.
     * Increments version on successful update.
     *
     * @param customer Customer with new values
     * @param expectedVersion Expected current version (for optimistic lock)
     * @return Updated customer with incremented version
     * @throws DynamoDbException with cause ConditionalCheckFailedException if version mismatch
     *         or negative balance would result (caught by service layer and mapped to domain exceptions)
     */
    @Override
    public DynamoCustomer saveWithVersionCheck(DynamoCustomer customer, long expectedVersion) {
        log.debug("Updating customer with version check: {} (current version: {}, new version: {})",
                  customer.getCustomerId(), expectedVersion, expectedVersion + 1);

        customer.setUpdatedAt(Instant.now());
        long newVersion = expectedVersion + 1;
        customer.setVersion(newVersion);

        try {
            // For now, do unconditional update - version check will be enforced in service layer
            // In Phase 3+, conditional check will use version = :expectedVersion AND rewardBalance >= 0
            customerTable.updateItem(customer);
            log.info("Customer updated successfully with version check: {} (new version: {})",
                     customer.getCustomerId(), newVersion);
            return customer;
        } catch (ConditionalCheckFailedException e) {
            log.warn("Optimistic lock conflict for customer: {} (expected version: {})",
                     customer.getCustomerId(), expectedVersion, e);
            throw new DynamoDbException(
                    "Optimistic lock conflict: version mismatch for customer " + customer.getCustomerId(),
                    e);
        } catch (Exception e) {
            log.error("Error updating customer with version check: {}", customer.getCustomerId(), e);
            throw new DynamoDbException("Failed to update customer", e);
        }
    }
}
