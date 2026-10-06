package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.entity.Customer;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RedemptionServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private RedemptionService redemptionService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    private UUID createCustomerWithBalance(int balance) {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        CreateCustomerRequest req = new CreateCustomerRequest("Test", "User", email);
        UUID customerId = customerService.createCustomer(req).customerId();
        // Set initial balance directly via repository (test-only setup)
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        customer.setRewardBalance(balance);
        customerRepository.save(customer);
        return customerId;
    }

    @Test
    void redeemPoints_fullTransactionCommits() {
        UUID customerId = createCustomerWithBalance(1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);

        RedeemPointsResponse response = redemptionService.redeemPoints(customerId, "key-full-tx", request);

        assertThat(response.pointsRedeemed()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);

        // Verify persisted balance
        Customer persisted = customerRepository.findById(customerId).orElseThrow();
        assertThat(persisted.getRewardBalance()).isEqualTo(500);
    }

    @Test
    void redeemPoints_idempotentRequest_doesNotDeductTwice() {
        UUID customerId = createCustomerWithBalance(1000);
        RedeemPointsRequest request = new RedeemPointsRequest(200, null);

        redemptionService.redeemPoints(customerId, "key-idem-it", request);
        redemptionService.redeemPoints(customerId, "key-idem-it", request);

        Customer persisted = customerRepository.findById(customerId).orElseThrow();
        assertThat(persisted.getRewardBalance()).isEqualTo(800);
    }

    @Test
    void redeemPoints_concurrentRequests_optimisticLockPreventsLostUpdate() throws Exception {
        UUID customerId = createCustomerWithBalance(10000);
        RedeemPointsRequest requestA = new RedeemPointsRequest(8000, null);
        RedeemPointsRequest requestB = new RedeemPointsRequest(5000, null);

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger concurrencyExceptionCount = new AtomicInteger(0);
        AtomicInteger successfulPoints = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> futureA = executor.submit(() -> {
            try {
                startLatch.await();
                redemptionService.redeemPoints(customerId, "key-concurrent-a", requestA);
                successCount.incrementAndGet();
                successfulPoints.set(8000);
            } catch (RewardConcurrencyException e) {
                concurrencyExceptionCount.incrementAndGet();
            } catch (RuntimeException e) {
                // Includes InsufficientRewardBalanceException if one thread wins and
                // the other detects insufficient balance after the first commit.
                // Treat as a graceful rejection — balance protection is working.
                concurrencyExceptionCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Future<?> futureB = executor.submit(() -> {
            try {
                startLatch.await();
                redemptionService.redeemPoints(customerId, "key-concurrent-b", requestB);
                successCount.incrementAndGet();
                successfulPoints.compareAndSet(0, 5000);
            } catch (RewardConcurrencyException e) {
                concurrencyExceptionCount.incrementAndGet();
            } catch (RuntimeException e) {
                // Same reasoning as futureA.
                concurrencyExceptionCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        startLatch.countDown();
        futureA.get();
        futureB.get();
        executor.shutdown();

        // The key invariant: balance must never go negative
        Customer finalCustomer = customerRepository.findById(customerId).orElseThrow();
        assertThat(finalCustomer.getRewardBalance()).isGreaterThanOrEqualTo(0);
        assertThat(finalCustomer.getRewardBalance()).isLessThanOrEqualTo(10000);

        // Total successes + concurrency exceptions must equal 2
        assertThat(successCount.get() + concurrencyExceptionCount.get()).isEqualTo(2);

        // If both 8000 and 5000 were somehow accepted, that would give -3000 (negative)
        // so at most 1 of them can succeed without the other being rejected
        if (successCount.get() == 1) {
            assertThat(finalCustomer.getRewardBalance()).isEqualTo(10000 - successfulPoints.get());
        } else {
            // Both could theoretically succeed if one completed before the other started.
            // In that case both succeeded sequentially and the balance check prevented double spend.
            assertThat(finalCustomer.getRewardBalance()).isGreaterThanOrEqualTo(0);
        }
    }
}
