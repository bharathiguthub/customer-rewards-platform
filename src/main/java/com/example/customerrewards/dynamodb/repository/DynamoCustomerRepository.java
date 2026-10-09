package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.models.DynamoCustomer;

import java.util.Optional;

/**
 * DynamoDB Repository interface for Customer persistence.
 *
 * Mirrors the JPA CustomerRepository interface.
 * Methods are to be implemented using AWS SDK v2 Enhanced Client
 * with conditional updates for optimistic locking and email uniqueness checks.
 */
public interface DynamoCustomerRepository {

    /**
     * Find customer by partition key (customerId).
     */
    Optional<DynamoCustomer> findById(String customerId);

    /**
     * Find customer by email using GSI.
     */
    Optional<DynamoCustomer> findByEmail(String email);

    /**
     * Check if customer exists by email using GSI.
     */
    boolean existsByEmail(String email);

    /**
     * Save or update customer.
     * For updates, enforces optimistic locking via version check.
     */
    DynamoCustomer save(DynamoCustomer customer);

    /**
     * Save or update customer with explicit version check.
     * For optimistic locking validation.
     */
    DynamoCustomer saveWithVersionCheck(DynamoCustomer customer, long expectedVersion);
}
