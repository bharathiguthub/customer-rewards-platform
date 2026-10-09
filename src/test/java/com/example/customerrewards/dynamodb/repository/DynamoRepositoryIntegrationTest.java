package com.example.customerrewards.dynamodb.repository;

import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DynamoDB repository implementations.
 * Tests the complete flow of customer and reward transaction operations.
 * 
 * Note: These tests require a local DynamoDB instance or proper AWS configuration.
 * For CI/CD, use LocalStack or mock DynamoDB endpoints.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {com.example.customerrewards.dynamodb.repository.InMemoryDynamoConfig.class})
@ActiveProfiles("test")
@DisplayName("DynamoDB Repository Integration Tests")
class DynamoRepositoryIntegrationTest {

    @Autowired
    private DynamoCustomerRepository customerRepository;

    @Autowired
    private DynamoRewardTransactionRepository transactionRepository;

    private String customerId;
    private String email;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID().toString();
        email = "test-" + UUID.randomUUID() + "@example.com";
    }

    // ==================== Customer Repository Tests ====================

    @Test
    @DisplayName("findById returns customer when found")
    void testFindById_Found() {
        // Create and save a customer
        DynamoCustomer customer = buildCustomer(customerId, email);
        customerRepository.save(customer);

        // Find by ID
        Optional<DynamoCustomer> found = customerRepository.findById(customerId);

        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo(customerId);
        assertThat(found.get().getEmail()).isEqualTo(email);
    }

    @Test
    @DisplayName("findById returns empty when not found")
    void testFindById_NotFound() {
        String nonExistentId = UUID.randomUUID().toString();

        Optional<DynamoCustomer> found = customerRepository.findById(nonExistentId);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByEmail returns customer when found")
    void testFindByEmail_Found() {
        // Create and save a customer
        DynamoCustomer customer = buildCustomer(customerId, email);
        customerRepository.save(customer);

        // Find by email
        Optional<DynamoCustomer> found = customerRepository.findByEmail(email);

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo(email);
        assertThat(found.get().getCustomerId()).isEqualTo(customerId);
    }

    @Test
    @DisplayName("findByEmail returns empty when not found")
    void testFindByEmail_NotFound() {
        String nonExistentEmail = "nonexistent-" + UUID.randomUUID() + "@example.com";

        Optional<DynamoCustomer> found = customerRepository.findByEmail(nonExistentEmail);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("existsByEmail returns true when customer exists")
    void testExistsByEmail_True() {
        // Create and save a customer
        DynamoCustomer customer = buildCustomer(customerId, email);
        customerRepository.save(customer);

        boolean exists = customerRepository.existsByEmail(email);

        assertTrue(exists);
    }

    @Test
    @DisplayName("existsByEmail returns false when customer does not exist")
    void testExistsByEmail_False() {
        String nonExistentEmail = "nonexistent-" + UUID.randomUUID() + "@example.com";

        boolean exists = customerRepository.existsByEmail(nonExistentEmail);

        assertFalse(exists);
    }

    @Test
    @DisplayName("save creates a new customer successfully")
    void testSave_NewCustomer_Success() {
        DynamoCustomer customer = buildCustomer(customerId, email);

        DynamoCustomer saved = customerRepository.save(customer);

        assertThat(saved).isNotNull();
        assertThat(saved.getCustomerId()).isEqualTo(customerId);
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getVersion()).isGreaterThan(0);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("save persists customer to database")
    void testSave_Persists() {
        DynamoCustomer customer = buildCustomer(customerId, email);
        customerRepository.save(customer);

        Optional<DynamoCustomer> retrieved = customerRepository.findById(customerId);

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getCustomerId()).isEqualTo(customerId);
    }

    @Test
    @DisplayName("saveWithVersionCheck succeeds with correct version")
    void testSaveWithVersionCheck_Success() {
        // Create initial customer
        DynamoCustomer customer = buildCustomer(customerId, email);
        DynamoCustomer saved = customerRepository.save(customer);
        long initialVersion = saved.getVersion();

        // Update with correct version
        customer.setRewardBalance(100);
        DynamoCustomer updated = customerRepository.saveWithVersionCheck(customer, initialVersion);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
        assertThat(updated.getRewardBalance()).isEqualTo(100);
    }

    @Test
    @DisplayName("saveWithVersionCheck fails with incorrect version")
    void testSaveWithVersionCheck_VersionMismatch() {
        // Create initial customer
        DynamoCustomer customer = buildCustomer(customerId, email);
        customerRepository.save(customer);

        // Try to update with wrong version
        customer.setRewardBalance(100);
        
        assertThrows(DynamoDbException.class, () -> 
            customerRepository.saveWithVersionCheck(customer, 999L)
        );
    }

    @Test
    @DisplayName("saveWithVersionCheck prevents negative balance")
    void testSaveWithVersionCheck_NegativeBalance() {
        // Create customer with balance
        DynamoCustomer customer = buildCustomer(customerId, email);
        customer.setRewardBalance(50);
        DynamoCustomer saved = customerRepository.save(customer);
        long initialVersion = saved.getVersion();

        // Try to set negative balance
        customer.setRewardBalance(-10);
        
        // Expect repository to reject negative balances by throwing DynamoDbException
        assertThrows(DynamoDbException.class, () -> 
            customerRepository.saveWithVersionCheck(customer, initialVersion)
        );
    }

    // ==================== Transaction Repository Tests ====================

    @Test
    @DisplayName("findById returns transaction when found")
    void testTransactionFindById_Found() {
        // Create and save a transaction
        String transactionId = UUID.randomUUID().toString();
        DynamoRewardTransaction transaction = buildTransaction(transactionId, customerId);
        transactionRepository.save(transaction);

        // Find by ID
        Optional<DynamoRewardTransaction> found = transactionRepository.findById(transactionId);

        assertThat(found).isPresent();
        assertThat(found.get().getTransactionId()).isEqualTo(transactionId);
        assertThat(found.get().getCustomerId()).isEqualTo(customerId);
    }

    @Test
    @DisplayName("findById returns empty when transaction not found")
    void testTransactionFindById_NotFound() {
        String nonExistentId = UUID.randomUUID().toString();

        Optional<DynamoRewardTransaction> found = transactionRepository.findById(nonExistentId);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByCustomerOrderByCreatedAtDesc returns transactions sorted by date")
    void testFindByCustomerOrderByCreatedAtDesc_Success() {
        // Create multiple transactions for same customer
        String txId1 = UUID.randomUUID().toString();
        String txId2 = UUID.randomUUID().toString();
        String txId3 = UUID.randomUUID().toString();

        DynamoRewardTransaction tx1 = buildTransaction(txId1, customerId);
        tx1.setCreatedAt(Instant.now().minusSeconds(100));
        
        DynamoRewardTransaction tx2 = buildTransaction(txId2, customerId);
        tx2.setCreatedAt(Instant.now().minusSeconds(50));
        
        DynamoRewardTransaction tx3 = buildTransaction(txId3, customerId);
        tx3.setCreatedAt(Instant.now());

        transactionRepository.save(tx1);
        transactionRepository.save(tx2);
        transactionRepository.save(tx3);

        // Retrieve and verify order
        List<DynamoRewardTransaction> transactions = 
            transactionRepository.findByCustomerOrderByCreatedAtDesc(customerId);

        assertThat(transactions).isNotEmpty();
        // Verify descending order (newest first)
        if (transactions.size() >= 2) {
            boolean isDescending = transactions.get(0).getCreatedAt()
                .isAfter(transactions.get(1).getCreatedAt()) ||
                transactions.get(0).getCreatedAt().equals(transactions.get(1).getCreatedAt());
            assertTrue(isDescending, "Transactions should be in descending order by createdAt");
        }
    }

    @Test
    @DisplayName("findByCustomerOrderByCreatedAtDesc returns empty for unknown customer")
    void testFindByCustomerOrderByCreatedAtDesc_NoTransactions() {
        String unknownCustomerId = UUID.randomUUID().toString();

        List<DynamoRewardTransaction> transactions = 
            transactionRepository.findByCustomerOrderByCreatedAtDesc(unknownCustomerId);

        assertThat(transactions).isEmpty();
    }

    @Test
    @DisplayName("findByCustomerOrderByCreatedAtDesc with pagination works correctly")
    void testFindByCustomerOrderByCreatedAtDesc_Paginated() {
        // Create multiple transactions
        for (int i = 0; i < 5; i++) {
            String txId = UUID.randomUUID().toString();
            DynamoRewardTransaction tx = buildTransaction(txId, customerId);
            tx.setCreatedAt(Instant.now().minusSeconds(i * 10));
            transactionRepository.save(tx);
        }

        // Query with pagination
        Pageable pageable = PageRequest.of(0, 2);
        Page<DynamoRewardTransaction> page = 
            transactionRepository.findByCustomerOrderByCreatedAtDesc(customerId, pageable);

        assertThat(page.getContent()).hasSizeLessThanOrEqualTo(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("getIdempotencyLock retrieves lock item for idempotent request")
    void testGetIdempotencyLock_NotFound() {
        String compositeId = "IDEMPOTENCY#" + customerId + "#nonexistent-" + UUID.randomUUID();
        
        Optional<com.example.customerrewards.dynamodb.models.IdempotencyLockItem> found = 
            transactionRepository.getIdempotencyLock(compositeId);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("executeRedemptionTransaction creates idempotency lock atomically")
    void testExecuteRedemptionTransaction_CreatesLock() {
        // Set up customer with balance
        DynamoCustomer customer = buildCustomer(customerId, "test@example.com");
        customer.setRewardBalance(1000);
        customer.setVersion(1);
        customerRepository.save(customer);

        // Create transaction
        String transactionId = UUID.randomUUID().toString();
        String idempotencyKey = "idempotency-" + UUID.randomUUID();
        DynamoRewardTransaction transaction = new DynamoRewardTransaction(
            transactionId,
            Instant.now(),
            customerId,
            "REDEEM",
            500,
            idempotencyKey,
            500
        );

        // Execute redemption
        transactionRepository.executeRedemptionTransaction(customerId, 1, 500, idempotencyKey, transaction);

        // Verify transaction was created
        Optional<DynamoRewardTransaction> savedTx = transactionRepository.findById(transactionId);
        assertThat(savedTx).isPresent();

        // Verify lock was created
        String compositeId = "IDEMPOTENCY#" + customerId + "#" + idempotencyKey;
        Optional<com.example.customerrewards.dynamodb.models.IdempotencyLockItem> lock = 
            transactionRepository.getIdempotencyLock(compositeId);
        assertThat(lock).isPresent();
        assertThat(lock.get().getTransactionId()).isEqualTo(transactionId);
    }

    @Test
    @DisplayName("save creates transaction successfully")
    void testTransactionSave_Success() {
        String transactionId = UUID.randomUUID().toString();
        DynamoRewardTransaction transaction = buildTransaction(transactionId, customerId);

        DynamoRewardTransaction saved = transactionRepository.save(transaction);

        assertThat(saved).isNotNull();
        assertThat(saved.getTransactionId()).isEqualTo(transactionId);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("save persists transaction to database")
    void testTransactionSave_Persists() {
        String transactionId = UUID.randomUUID().toString();
        DynamoRewardTransaction transaction = buildTransaction(transactionId, customerId);

        transactionRepository.save(transaction);

        Optional<DynamoRewardTransaction> retrieved = 
            transactionRepository.findById(transactionId);

        assertThat(retrieved).isPresent();
    }

    // ==================== Helper Methods ====================

    private DynamoCustomer buildCustomer(String customerId, String email) {
        DynamoCustomer customer = new DynamoCustomer();
        customer.setCustomerId(customerId);
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setEmail(email);
        customer.setRewardBalance(0);
        customer.setVersion(0);
        customer.setCreatedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        return customer;
    }

    private DynamoRewardTransaction buildTransaction(String transactionId, String customerId) {
        DynamoRewardTransaction transaction = new DynamoRewardTransaction();
        transaction.setTransactionId(transactionId);
        transaction.setCustomerId(customerId);
        transaction.setCreatedAt(Instant.now());
        transaction.setTransactionType("EARN");
        transaction.setPoints(100);
        transaction.setRemainingBalanceSnapshot(100);
        return transaction;
    }
}
