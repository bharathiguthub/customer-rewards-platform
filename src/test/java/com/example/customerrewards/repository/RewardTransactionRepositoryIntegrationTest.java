package com.example.customerrewards.repository;

import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import com.example.customerrewards.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class RewardTransactionRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RewardTransactionRepository transactionRepository;

    private Customer persistCustomer() {
        Customer customer = new Customer();
        customer.setFirstName("Bob");
        customer.setLastName("Smith");
        customer.setEmail("bob." + UUID.randomUUID() + "@example.com");
        customer.setRewardBalance(0);
        customerRepository.save(customer);
        entityManager.flush();
        return customer;
    }

    private RewardTransaction buildTransaction(Customer customer, int points, String idempotencyKey) {
        RewardTransaction tx = new RewardTransaction();
        tx.setCustomer(customer);
        tx.setType(TransactionType.EARN);
        tx.setPoints(points);
        tx.setIdempotencyKey(idempotencyKey);
        return tx;
    }

    @Test
    void givenValidTransaction_whenSave_thenPersisted() {
        Customer customer = persistCustomer();

        RewardTransaction tx = buildTransaction(customer, 100, null);
        transactionRepository.save(tx);
        entityManager.flush();

        assertThat(tx.getId()).isNotNull();
        assertThat(tx.getType()).isEqualTo(TransactionType.EARN);
        assertThat(tx.getPoints()).isEqualTo(100);
    }

    @Test
    void givenDuplicateIdempotencyKey_whenSave_thenThrowsDataIntegrityViolationException() {
        Customer customer = persistCustomer();
        String key = "key-123";

        RewardTransaction first = buildTransaction(customer, 100, key);
        transactionRepository.save(first);
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class, () -> {
            RewardTransaction second = buildTransaction(customer, 200, key);
            transactionRepository.save(second);
            entityManager.flush();
        });
    }

    @Test
    void givenNullIdempotencyKey_whenSaveTwoTransactions_thenBothPersist() {
        Customer customer = persistCustomer();

        RewardTransaction first = buildTransaction(customer, 100, null);
        transactionRepository.save(first);
        entityManager.flush();

        RewardTransaction second = buildTransaction(customer, 200, null);
        transactionRepository.save(second);
        entityManager.flush();

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull();
    }

    @Test
    void givenTransactionWithZeroPoints_whenSave_thenThrowsDataIntegrityViolationException() {
        Customer customer = persistCustomer();

        assertThrows(DataIntegrityViolationException.class, () -> {
            RewardTransaction tx = buildTransaction(customer, 0, null);
            transactionRepository.save(tx);
            entityManager.flush();
        });
    }

    @Test
    void givenCustomerWithTransactions_whenFindByCustomerOrderByCreatedAtDesc_thenReturnsSorted()
            throws InterruptedException {
        Customer customer = persistCustomer();

        RewardTransaction first = buildTransaction(customer, 100, null);
        transactionRepository.save(first);
        entityManager.flush();

        Thread.sleep(100);

        RewardTransaction second = buildTransaction(customer, 200, null);
        transactionRepository.save(second);
        entityManager.flush();

        List<RewardTransaction> results =
                transactionRepository.findByCustomerOrderByCreatedAtDesc(customer);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getCreatedAt())
                .isAfterOrEqualTo(results.get(1).getCreatedAt());
    }
}
