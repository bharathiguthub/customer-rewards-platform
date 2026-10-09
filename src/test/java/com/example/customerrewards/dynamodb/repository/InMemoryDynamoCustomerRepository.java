package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryDynamoCustomerRepository implements DynamoCustomerRepository {

    private static InMemoryDynamoCustomerRepository INSTANCE;

    private final Map<String, DynamoCustomer> store = new ConcurrentHashMap<>();
    private final Map<String, String> emailIndex = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(1);

    public InMemoryDynamoCustomerRepository() {
        INSTANCE = this;
    }

    public static InMemoryDynamoCustomerRepository getInstance() {
        return INSTANCE;
    }

    @Override
    public Optional<DynamoCustomer> findById(String customerId) {
        return Optional.ofNullable(store.get(customerId));
    }

    @Override
    public Optional<DynamoCustomer> findByEmail(String email) {
        String id = emailIndex.get(email);
        if (id == null) return Optional.empty();
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsByEmail(String email) {
        return emailIndex.containsKey(email);
    }

    @Override
    public DynamoCustomer save(DynamoCustomer customer) {
        // Enforce email uniqueness
        if (customer.getEmail() != null && emailIndex.containsKey(customer.getEmail())) {
            throw new DynamoDbException("Email already in use: " + customer.getEmail());
        }
        if (customer.getCreatedAt() == null) customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        if (customer.getVersion() <= 0) customer.setVersion(1);
        store.put(customer.getCustomerId(), deepCopy(customer));
        if (customer.getEmail() != null) emailIndex.put(customer.getEmail(), customer.getCustomerId());
        return deepCopy(customer);
    }

    @Override
    public DynamoCustomer saveWithVersionCheck(DynamoCustomer customer, long expectedVersion) {
        DynamoCustomer existing = store.get(customer.getCustomerId());
        if (existing == null) {
            throw new DynamoDbException("Customer not found: " + customer.getCustomerId());
        }
        if (existing.getVersion() != expectedVersion) {
            throw new DynamoDbException("Version mismatch for customer: " + customer.getCustomerId());
        }
        // Prevent negative balance
        if (customer.getRewardBalance() < 0) {
            throw new DynamoDbException("Insufficient reward balance. Requested negative balance");
        }
        long newVersion = expectedVersion + 1;
        customer.setVersion(newVersion);
        customer.setUpdatedAt(Instant.now());
        store.put(customer.getCustomerId(), deepCopy(customer));
        return deepCopy(customer);
    }

    private DynamoCustomer deepCopy(DynamoCustomer c) {
        DynamoCustomer copy = new DynamoCustomer();
        copy.setCustomerId(c.getCustomerId());
        copy.setFirstName(c.getFirstName());
        copy.setLastName(c.getLastName());
        copy.setEmail(c.getEmail());
        copy.setRewardBalance(c.getRewardBalance());
        copy.setVersion(c.getVersion());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        return copy;
    }
}
