package com.example.customerrewards.dynamodb.models;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;

/**
 * DynamoDB persistence model for Customer.
 *
 * Mapped using AWS SDK v2 Enhanced Client annotations.
 * 
 * Primary Key Design:
 * - Partition key: customerId (UUID as String)
 * - No sort key (single-item read/write pattern)
 *
 * GSI Design:
 * - GSI1-PK: email (for efficient customer lookup by email, duplicate detection)
 * - No GSI sort key (email lookups return 0 or 1 item)
 *
 * Attributes:
 * - customerId: Partition key, customer UUID as string
 * - firstName, lastName: Customer name
 * - email: Unique email address (GSI1 partition key, enforced via conditional PutItem)
 * - rewardBalance: Current accumulated reward points
 * - version: Optimistic locking counter (manual version management)
 * - createdAt, updatedAt: Timestamps (ISO-8601 UTC)
 *
 * Optimistic Locking:
 * - UpdateItem uses condition: version = :expectedVersion
 * - Successful update increments version atomically
 * - Version mismatch → ConditionalCheckFailedException → RewardConcurrencyException
 *
 * Email Uniqueness:
 * - PutItem uses condition: attribute_not_exists(email)
 * - UpdateItem does NOT update email (immutable after creation)
 * - Application must query by email before PutItem to detect duplicates
 */
@DynamoDbBean
public class DynamoCustomer {

    private String customerId;
    private String firstName;
    private String lastName;
    private String email;
    private int rewardBalance;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // Constructors
    public DynamoCustomer() {
        this.rewardBalance = 0;
        this.version = 0;
    }

    public DynamoCustomer(String customerId, String firstName, String lastName, String email,
                          int rewardBalance, long version, Instant createdAt, Instant updatedAt) {
        this.customerId = customerId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.rewardBalance = rewardBalance;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters

    @DynamoDbPartitionKey
    @DynamoDbAttribute("customerId")
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    @DynamoDbAttribute("firstName")
    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    @DynamoDbAttribute("lastName")
    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    @DynamoDbAttribute("email")
    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @DynamoDbAttribute("rewardBalance")
    public int getRewardBalance() {
        return rewardBalance;
    }

    public void setRewardBalance(int rewardBalance) {
        this.rewardBalance = rewardBalance;
    }

    @DynamoDbAttribute("version")
    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
