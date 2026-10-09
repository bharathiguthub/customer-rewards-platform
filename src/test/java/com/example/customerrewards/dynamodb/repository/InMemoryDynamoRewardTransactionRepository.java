package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.models.IdempotencyLockItem;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class InMemoryDynamoRewardTransactionRepository implements DynamoRewardTransactionRepository {

    private final Map<String, DynamoRewardTransaction> store = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyLockItem> lockStore = new ConcurrentHashMap<>();
    private final Map<String, List<DynamoRewardTransaction>> byCustomer = new ConcurrentHashMap<>();

    @Override
    public Optional<DynamoRewardTransaction> findById(String transactionId) {
        return Optional.ofNullable(store.get(transactionId));
    }

    @Override
    public void executeRedemptionTransaction(String customerId, long expectedCustomerVersion, int pointsToRedeem, String idempotencyKey, DynamoRewardTransaction transaction) {
        // Simple in-memory atomic operation with idempotency lock
        InMemoryDynamoCustomerRepository customerRepo = InMemoryDynamoCustomerRepository.getInstance();
        if (customerRepo == null) {
            throw new DynamoDbException("Customer repository not available in test context");
        }

        synchronized (customerId.intern()) {
            var optCustomer = customerRepo.findById(customerId);
            if (optCustomer.isEmpty()) {
                throw new DynamoDbException("Customer not found: " + customerId);
            }
            var customer = optCustomer.get();
            if (customer.getVersion() != expectedCustomerVersion) {
                throw new DynamoDbException("Version mismatch for customer: " + customerId);
            }
            if (customer.getRewardBalance() < pointsToRedeem) {
                throw new DynamoDbException("Insufficient reward balance. Requested: " + pointsToRedeem + ", available: " + customer.getRewardBalance());
            }
            
            // Idempotency lock check: ensure composite key doesn't already exist
            String compositeId = "IDEMPOTENCY#" + customerId + "#" + idempotencyKey;
            if (lockStore.containsKey(compositeId)) {
                throw new DynamoDbException("ConditionalCheckFailedException lockId");
            }

            // perform updates
            customer.setRewardBalance(customer.getRewardBalance() - pointsToRedeem);
            try {
                customerRepo.saveWithVersionCheck(customer, expectedCustomerVersion);
            } catch (DynamoDbException e) {
                throw new DynamoDbException("Failed to update customer during redemption: " + e.getMessage(), e);
            }

            // save transaction
            transaction.setRemainingBalanceSnapshot(customer.getRewardBalance());
            save(transaction);
            
            // Create idempotency lock item (atomic with transaction creation)
            IdempotencyLockItem lock = new IdempotencyLockItem(
                compositeId,
                transaction.getTransactionId(),
                customerId,
                Instant.now()
            );
            lockStore.put(compositeId, lock);
        }
    }

    @Override
    public List<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId) {
        List<DynamoRewardTransaction> list = byCustomer.getOrDefault(customerId, Collections.emptyList());
        return list.stream()
                .sorted((a,b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @Override
    public org.springframework.data.domain.Page<DynamoRewardTransaction> findByCustomerOrderByCreatedAtDesc(String customerId, org.springframework.data.domain.Pageable pageable) {
        List<DynamoRewardTransaction> all = findByCustomerOrderByCreatedAtDesc(customerId);
        int start = pageable.getPageNumber()*pageable.getPageSize();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<DynamoRewardTransaction> content = start < all.size() ? all.subList(start,end) : Collections.emptyList();
        return new org.springframework.data.domain.PageImpl<>(content, pageable, all.size());
    }

    @Override
    public Optional<IdempotencyLockItem> getIdempotencyLock(String compositeIdempotencyId) {
        return Optional.ofNullable(lockStore.get(compositeIdempotencyId));
    }

    @Override
    public DynamoRewardTransaction save(DynamoRewardTransaction transaction) {
        if (transaction.getCreatedAt() == null) transaction.setCreatedAt(Instant.now());
        store.put(transaction.getTransactionId(), deepCopy(transaction));
        byCustomer.computeIfAbsent(transaction.getCustomerId(), k -> new CopyOnWriteArrayList<>())
                .add(deepCopy(transaction));
        return deepCopy(transaction);
    }

    private DynamoRewardTransaction deepCopy(DynamoRewardTransaction t) {
        DynamoRewardTransaction copy = new DynamoRewardTransaction();
        copy.setTransactionId(t.getTransactionId());
        copy.setCreatedAt(t.getCreatedAt());
        copy.setCustomerId(t.getCustomerId());
        copy.setTransactionType(t.getTransactionType());
        copy.setPoints(t.getPoints());
        copy.setIdempotencyKey(t.getIdempotencyKey());
        copy.setRemainingBalanceSnapshot(t.getRemainingBalanceSnapshot());
        return copy;
    }
}
