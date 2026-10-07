package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import com.example.customerrewards.entity.TransactionType;
import com.example.customerrewards.exception.InsufficientRewardBalanceException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
import com.example.customerrewards.service.impl.RedemptionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedemptionServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private RewardTransactionRepository rewardTransactionRepository;

    @InjectMocks
    private RedemptionServiceImpl redemptionService;

    private Customer buildCustomer(UUID id, int balance) {
        Customer c = new Customer();
        c.setId(id);
        c.setFirstName("Alice");
        c.setLastName("Nguyen");
        c.setEmail("alice@example.com");
        c.setRewardBalance(balance);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }

    private RewardTransaction buildTransaction(UUID txId, Customer customer, int points,
                                               String idempotencyKey, int remainingBalanceSnapshot) {
        RewardTransaction tx = new RewardTransaction();
        tx.setId(txId);
        tx.setCustomer(customer);
        tx.setType(TransactionType.REDEEM);
        tx.setPoints(points);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRemainingBalanceSnapshot(remainingBalanceSnapshot);
        tx.setCreatedAt(Instant.now());
        return tx;
    }

    @Test
    void redeemPoints_success() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, 1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-success";

        when(rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey))
            .thenReturn(Optional.empty());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenReturn(customer);
        when(rewardTransactionRepository.saveAndFlush(any(RewardTransaction.class)))
            .thenAnswer(inv -> {
                RewardTransaction tx = inv.getArgument(0);
                tx.setId(UUID.randomUUID());
                return tx;
            });

        RedeemPointsResponse response = redemptionService.redeemPoints(customerId, idempotencyKey, request);

        assertThat(response.pointsRedeemed()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);
        assertThat(response.customerId()).isEqualTo(customerId);
    }

    @Test
    void redeemPoints_insufficientBalance_throwsInsufficientRewardBalanceException() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, 100);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);

        when(rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        assertThrows(InsufficientRewardBalanceException.class,
            () -> redemptionService.redeemPoints(customerId, "key-insuf", request));
    }

    @Test
    void redeemPoints_zeroPoints_throwsInvalidRewardPointsException() {
        UUID customerId = UUID.randomUUID();
        RedeemPointsRequest request = new RedeemPointsRequest(0, null);

        assertThrows(InvalidRewardPointsException.class,
            () -> redemptionService.redeemPoints(customerId, "key-zero", request));
    }

    @Test
    void redeemPoints_negativePoints_throwsInvalidRewardPointsException() {
        UUID customerId = UUID.randomUUID();
        RedeemPointsRequest request = new RedeemPointsRequest(-1, null);

        assertThrows(InvalidRewardPointsException.class,
            () -> redemptionService.redeemPoints(customerId, "key-neg", request));
    }

    @Test
    void redeemPoints_idempotentKey_returnsExistingResult() {
        UUID customerId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, 500);
        // Snapshot = 700: the balance recorded at original commit time.
        // Current customer balance is 500 (changed by a later transaction).
        // Replay must return 700, not 500.
        RewardTransaction existing = buildTransaction(txId, customer, 300, "key-idem", 700);
        RedeemPointsRequest request = new RedeemPointsRequest(300, null);

        when(rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(customerId, "key-idem"))
            .thenReturn(Optional.of(existing));

        RedeemPointsResponse response = redemptionService.redeemPoints(customerId, "key-idem", request);

        assertThat(response.transactionId()).isEqualTo(txId);
        assertThat(response.pointsRedeemed()).isEqualTo(300);
        // Must return the snapshot (700), not the current balance (500)
        assertThat(response.remainingBalance()).isEqualTo(700);
        // Customer must not be loaded for a replay
        verify(customerRepository, never()).findById(any());
    }

    @Test
    void redeemPoints_idempotentKey_doesNotDeductBalanceTwice() {
        UUID customerId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, 500);
        RewardTransaction existing = buildTransaction(txId, customer, 300, "key-idem2", 700);
        RedeemPointsRequest request = new RedeemPointsRequest(300, null);

        when(rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(customerId, "key-idem2"))
            .thenReturn(Optional.of(existing));

        redemptionService.redeemPoints(customerId, "key-idem2", request);

        verify(customerRepository, never()).findById(any());
        verify(customerRepository, never()).save(any());
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void redeemPoints_optimisticLockConflict_throwsRewardConcurrencyException() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, 1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);

        when(rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class)))
            .thenThrow(new ObjectOptimisticLockingFailureException(Customer.class, customerId));

        RewardConcurrencyException ex = assertThrows(RewardConcurrencyException.class,
            () -> redemptionService.redeemPoints(customerId, "key-lock", request));

        assertThat(ex.isRetryable()).isTrue();
    }
}
