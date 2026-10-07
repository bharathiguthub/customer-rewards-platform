package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EarnServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private EarnService earnService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void testEarnPoints_Success() {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("Alice", "Nguyen", "alice@example.com"));
        UUID customerId = customer.customerId();
        EarnPointsRequest request = new EarnPointsRequest(500);

        // Act
        EarnPointsResponse response = earnService.earnPoints(customerId, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.points()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);

        // Verify balance was updated
        CustomerResponse updated = customerService.getCustomer(customerId);
        assertThat(updated.rewardBalance()).isEqualTo(500);
    }

    @Test
    void testEarnPoints_MultipleEarns() {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("Bob", "Smith", "bob@example.com"));
        UUID customerId = customer.customerId();

        // Act
        EarnPointsResponse response1 = earnService.earnPoints(customerId, new EarnPointsRequest(100));
        EarnPointsResponse response2 = earnService.earnPoints(customerId, new EarnPointsRequest(200));
        EarnPointsResponse response3 = earnService.earnPoints(customerId, new EarnPointsRequest(300));

        // Assert
        assertThat(response1.remainingBalance()).isEqualTo(100);
        assertThat(response2.remainingBalance()).isEqualTo(300);
        assertThat(response3.remainingBalance()).isEqualTo(600);

        CustomerResponse updated = customerService.getCustomer(customerId);
        assertThat(updated.rewardBalance()).isEqualTo(600);
    }

    @Test
    void testEarnPoints_CustomerNotFound() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(100);

        // Act & Assert
        assertThrows(CustomerNotFoundException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_InvalidPoints_Zero() {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("Carol", "Davis", "carol@example.com"));
        UUID customerId = customer.customerId();
        EarnPointsRequest request = new EarnPointsRequest(0);

        // Act & Assert
        assertThrows(InvalidRewardPointsException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_InvalidPoints_Negative() {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("David", "Miller", "david@example.com"));
        UUID customerId = customer.customerId();
        EarnPointsRequest request = new EarnPointsRequest(-100);

        // Act & Assert
        assertThrows(InvalidRewardPointsException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_ConcurrentEarns() throws Exception {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("Eve", "Wilson", "eve@example.com"));
        UUID customerId = customer.customerId();

        int threadCount = 5;
        int pointsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // Act
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    EarnPointsResponse response = earnService.earnPoints(customerId,
                            new EarnPointsRequest(pointsPerThread));
                    assertThat(response).isNotNull();
                    successCount.incrementAndGet();
                } catch (RewardConcurrencyException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // Wait for all tasks
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        // Assert: All threads should succeed (optimistic locking with version increment)
        // or some may retry, but we should have at least one success
        assertThat(successCount.get()).isGreaterThan(0);

        // Verify final balance reflects earned points
        CustomerResponse updated = customerService.getCustomer(customerId);
        assertThat(updated.rewardBalance()).isGreaterThanOrEqualTo(pointsPerThread);
    }

    @Test
    void testEarnPoints_TransactionCreated() {
        // Arrange
        CustomerResponse customer = customerService.createCustomer(
                new CreateCustomerRequest("Frank", "Brown", "frank@example.com"));
        UUID customerId = customer.customerId();
        EarnPointsRequest request = new EarnPointsRequest(250);

        // Act
        EarnPointsResponse response = earnService.earnPoints(customerId, request);

        // Assert: Transaction was created with correct data
        assertThat(response.transactionId()).isNotNull();
        assertThat(response.points()).isEqualTo(250);
        assertThat(response.remainingBalance()).isEqualTo(250);
        assertThat(response.createdAt()).isNotNull();
    }
}
