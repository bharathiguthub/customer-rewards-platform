package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
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

    @Autowired
    private RewardTransactionRepository rewardTransactionRepository;

    // -----------------------------------------------------------------------
    // Test setup helper
    // -----------------------------------------------------------------------

    /**
     * Creates a customer via the service, then directly sets their initial balance
     * via the repository. Using the repository here is intentional test setup —
     * there is no EARN endpoint in Phase 3.
     */
    private UUID createCustomerWithBalance(int balance) {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        UUID customerId = customerService.createCustomer(
                new CreateCustomerRequest("Test", "User", email)).customerId();
        Customer customer = customerRepository.findById(customerId).orElseThrow();
        customer.setRewardBalance(balance);
        customerRepository.save(customer);
        return customerId;
    }

    // -----------------------------------------------------------------------
    // Scenario A: Sequential idempotent replay returns the original result
    // -----------------------------------------------------------------------

    @Test
    void scenarioA_sequentialIdempotentReplay_returnsOriginalResult() {
        UUID customerId = createCustomerWithBalance(1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);

        // First call
        RedeemPointsResponse first = redemptionService.redeemPoints(customerId, "key-a", request);
        assertThat(first.pointsRedeemed()).isEqualTo(500);
        assertThat(first.remainingBalance()).isEqualTo(500);

        // Replay — same key, same request
        RedeemPointsResponse replay = redemptionService.redeemPoints(customerId, "key-a", request);

        // Replay must return the exact same result
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.pointsRedeemed()).isEqualTo(500);
        assertThat(replay.remainingBalance()).isEqualTo(500);

        // Balance must only have been deducted once
        Customer persisted = customerRepository.findById(customerId).orElseThrow();
        assertThat(persisted.getRewardBalance()).isEqualTo(500);
    }

    // -----------------------------------------------------------------------
    // Scenario B: Replay after another transaction changed the balance
    //             must still return the ORIGINAL remainingBalanceSnapshot
    // -----------------------------------------------------------------------

    @Test
    void scenarioB_replayAfterBalanceChanged_returnsOriginalSnapshot() {
        UUID customerId = createCustomerWithBalance(10_000);

        // Original redemption: 2,000 points → snapshot = 8,000
        RedeemPointsResponse original =
                redemptionService.redeemPoints(customerId, "key-b-original", new RedeemPointsRequest(2000, null));
        assertThat(original.remainingBalance()).isEqualTo(8_000);

        // Another redemption with a different key reduces balance further to 5,000
        redemptionService.redeemPoints(customerId, "key-b-second", new RedeemPointsRequest(3000, null));
        Customer afterSecond = customerRepository.findById(customerId).orElseThrow();
        assertThat(afterSecond.getRewardBalance()).isEqualTo(5_000);

        // Replay of the ORIGINAL key — must return 8,000 (the snapshot), not 5,000
        RedeemPointsResponse replay =
                redemptionService.redeemPoints(customerId, "key-b-original", new RedeemPointsRequest(2000, null));

        assertThat(replay.transactionId()).isEqualTo(original.transactionId());
        assertThat(replay.remainingBalance())
                .as("Replay must return the original snapshot (8000), not the current balance (5000)")
                .isEqualTo(8_000);

        // Balance must not have been deducted again
        Customer afterReplay = customerRepository.findById(customerId).orElseThrow();
        assertThat(afterReplay.getRewardBalance()).isEqualTo(5_000);
    }

    // -----------------------------------------------------------------------
    // Scenario C: Two CONCURRENT requests with the SAME idempotency key
    //
    // Under true concurrency (CyclicBarrier), both threads read the same customer
    // version. Both attempt saveAndFlush(customer), which includes a
    // WHERE version = ? predicate. Only one UPDATE matches — the other raises
    // ObjectOptimisticLockingFailureException → RewardConcurrencyException before
    // it ever reaches the transaction INSERT. This means the DataIntegrityViolation
    // backstop on the INSERT is the fallback for a race where the second thread's
    // customer UPDATE somehow succeeds (e.g., if optimistic locking isn't triggered
    // because reads happen at slightly different times).
    //
    // The critical invariant regardless of execution order:
    //   - Points are deducted AT MOST ONCE (never twice)
    //   - Exactly 0 or 1 RewardTransaction rows exist for the shared key
    //   - The balance is non-negative
    //   - The second thread either gets an idempotent replay (sequential case)
    //     or a RewardConcurrencyException (concurrent case — retry required)
    // -----------------------------------------------------------------------

    @Test
    void scenarioC_concurrentSameKey_deductsPointsAtMostOnce() throws Exception {
        UUID customerId = createCustomerWithBalance(10_000);
        RedeemPointsRequest request = new RedeemPointsRequest(3000, null);
        String sharedKey = "key-c-same-" + UUID.randomUUID();

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger concurrencyExceptionCount = new AtomicInteger(0);
        List<RedeemPointsResponse> responses = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Runnable task = () -> {
            try {
                barrier.await();
                RedeemPointsResponse resp = redemptionService.redeemPoints(customerId, sharedKey, request);
                synchronized (responses) {
                    responses.add(resp);
                }
                successCount.incrementAndGet();
            } catch (RewardConcurrencyException e) {
                // Expected under true concurrency: the losing thread hits the optimistic
                // lock before it can insert a transaction. The caller must retry, at which
                // point the idempotency check will find the winning transaction and replay it.
                concurrencyExceptionCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException("Unexpected exception in concurrent same-key test", e);
            }
        };

        Future<?> f1 = executor.submit(task);
        Future<?> f2 = executor.submit(task);
        f1.get();
        f2.get();
        executor.shutdown();

        // Every request must be accounted for: success or RewardConcurrencyException
        assertThat(successCount.get() + concurrencyExceptionCount.get()).isEqualTo(2);

        // Points must be deducted AT MOST ONCE (never twice)
        Customer finalCustomer = customerRepository.findById(customerId).orElseThrow();
        assertThat(finalCustomer.getRewardBalance())
                .as("Balance must never go below 10000 - 3000 = 7000 (deducted at most once)")
                .isGreaterThanOrEqualTo(7_000);

        // At most one transaction row for the shared key
        long txCount = rewardTransactionRepository.findAll().stream()
                .filter(tx -> sharedKey.equals(tx.getIdempotencyKey()))
                .count();
        assertThat(txCount)
                .as("At most one RewardTransaction must exist for the shared key")
                .isLessThanOrEqualTo(1);

        if (successCount.get() == 2) {
            // Sequential execution: both succeeded. The idempotency check caught the
            // second request and returned the original result. Exactly 1 transaction exists.
            assertThat(txCount).isEqualTo(1);
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).transactionId()).isEqualTo(responses.get(1).transactionId());
            assertThat(finalCustomer.getRewardBalance()).isEqualTo(7_000);
        } else {
            // Concurrent execution: one thread won the optimistic lock, one threw
            // RewardConcurrencyException. Exactly 1 transaction exists (or 0 if
            // neither reached the INSERT before the lock fired, which can't happen
            // since successCount >= 1).
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(finalCustomer.getRewardBalance()).isEqualTo(7_000);
        }
    }

    // -----------------------------------------------------------------------
    // Scenario D: Two CONCURRENT requests with DIFFERENT idempotency keys
    //             Optimistic locking prevents lost update, balance never negative
    // -----------------------------------------------------------------------

    @Test
    void scenarioD_concurrentDifferentKeys_optimisticLockPreventsLostUpdate() throws Exception {
        UUID customerId = createCustomerWithBalance(10_000);
        RedeemPointsRequest requestA = new RedeemPointsRequest(8_000, null);
        RedeemPointsRequest requestB = new RedeemPointsRequest(5_000, null);

        // CyclicBarrier forces both threads to start at the same moment, ensuring both
        // read the same customer version (version=N) before either commits. When both
        // attempt saveAndFlush, one will succeed (version → N+1) and the other will get
        // ObjectOptimisticLockingFailureException because version=N no longer exists.
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger concurrencyExceptionCount = new AtomicInteger(0);
        AtomicInteger successfulPoints = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> futureA = executor.submit(() -> {
            try {
                barrier.await();
                redemptionService.redeemPoints(customerId, "key-d-a", requestA);
                successCount.incrementAndGet();
                successfulPoints.set(8_000);
            } catch (RewardConcurrencyException e) {
                concurrencyExceptionCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> futureB = executor.submit(() -> {
            try {
                barrier.await();
                redemptionService.redeemPoints(customerId, "key-d-b", requestB);
                successCount.incrementAndGet();
                successfulPoints.compareAndSet(0, 5_000);
            } catch (RewardConcurrencyException e) {
                concurrencyExceptionCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        futureA.get();
        futureB.get();
        executor.shutdown();

        Customer finalCustomer = customerRepository.findById(customerId).orElseThrow();
        int finalBalance = finalCustomer.getRewardBalance();

        // The balance must never be negative
        assertThat(finalBalance)
                .as("Balance must never go negative")
                .isGreaterThanOrEqualTo(0);

        // Total of successes + concurrency exceptions must equal 2 (every request accounted for)
        assertThat(successCount.get() + concurrencyExceptionCount.get())
                .as("Every request must be accounted for: success or RewardConcurrencyException")
                .isEqualTo(2);

        if (successCount.get() == 1 && concurrencyExceptionCount.get() == 1) {
            // The expected path under genuine concurrency: one won, one got a lock conflict
            assertThat(finalBalance)
                    .as("Balance must equal 10000 minus the one successful deduction")
                    .isEqualTo(10_000 - successfulPoints.get());
        } else if (successCount.get() == 2) {
            // Sequential execution: both succeeded. This is valid — the second request
            // ran after the first committed, so the balance check fired correctly.
            // 8000 + 5000 = 13000 > 10000, so both cannot succeed without
            // the second hitting InsufficientRewardBalanceException — but if
            // A(8000) succeeded first, B(5000) would fail the balance check (balance=2000).
            // So successCount==2 is only reachable if the smaller request committed first.
            assertThat(finalBalance).isGreaterThanOrEqualTo(0);
        }

        // In all cases: the balance must equal 10000 minus whatever was actually deducted
        // Verify by counting committed transactions
        long txCount = rewardTransactionRepository.findAll().stream()
                .filter(tx -> tx.getIdempotencyKey() != null &&
                        (tx.getIdempotencyKey().equals("key-d-a") || tx.getIdempotencyKey().equals("key-d-b")))
                .count();
        int expectedBalance = 10_000 - (int) rewardTransactionRepository.findAll().stream()
                .filter(tx -> tx.getIdempotencyKey() != null &&
                        (tx.getIdempotencyKey().equals("key-d-a") || tx.getIdempotencyKey().equals("key-d-b")))
                .mapToInt(tx -> tx.getPoints())
                .sum();
        assertThat(finalBalance)
                .as("Balance must exactly match 10000 minus committed transaction points")
                .isEqualTo(expectedBalance);
    }
}
