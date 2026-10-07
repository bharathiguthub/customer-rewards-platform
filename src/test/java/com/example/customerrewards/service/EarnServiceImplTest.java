package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import com.example.customerrewards.entity.TransactionType;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
import com.example.customerrewards.service.impl.EarnServiceImpl;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EarnServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private RewardTransactionRepository rewardTransactionRepository;

    @InjectMocks
    private EarnServiceImpl earnService;

    private Customer buildCustomer(UUID id, String email, int balance) {
        Customer c = new Customer();
        c.setId(id);
        c.setFirstName("Alice");
        c.setLastName("Nguyen");
        c.setEmail(email);
        c.setRewardBalance(balance);
        c.setVersion(1L);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }

    @Test
    void testEarnPoints_Success() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, "alice@example.com", 500);
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenReturn(customer);
        when(rewardTransactionRepository.saveAndFlush(any(RewardTransaction.class))).thenAnswer(invocation -> {
            RewardTransaction tx = invocation.getArgument(0);
            tx.setId(UUID.randomUUID());
            tx.setCreatedAt(Instant.now());
            return tx;
        });

        EarnPointsResponse response = earnService.earnPoints(customerId, request);

        assertThat(response).isNotNull();
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.points()).isEqualTo(100);
        assertThat(response.remainingBalance()).isEqualTo(600);
        assertThat(response.transactionId()).isNotNull();

        verify(customerRepository).saveAndFlush(customer);
        assertThat(customer.getRewardBalance()).isEqualTo(600);
    }

    @Test
    void testEarnPoints_CustomerNotFound() {
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_InvalidPoints_Zero() {
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(0);

        assertThrows(InvalidRewardPointsException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_InvalidPoints_Negative() {
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(-50);

        assertThrows(InvalidRewardPointsException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_IntegerOverflow() {
        UUID customerId = UUID.randomUUID();
        // Current balance is near Integer.MAX_VALUE, adding more will overflow
        Customer customer = buildCustomer(customerId, "alice@example.com", Integer.MAX_VALUE - 100);
        EarnPointsRequest request = new EarnPointsRequest(200);

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        assertThrows(IllegalArgumentException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_OptimisticLockingConflict() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, "alice@example.com", 500);
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException("version conflict", new RuntimeException()));

        assertThrows(RewardConcurrencyException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_LargeBalance() {
        UUID customerId = UUID.randomUUID();
        Customer customer = buildCustomer(customerId, "alice@example.com", 1_000_000);
        EarnPointsRequest request = new EarnPointsRequest(5_000);

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class))).thenReturn(customer);
        when(rewardTransactionRepository.saveAndFlush(any(RewardTransaction.class))).thenAnswer(invocation -> {
            RewardTransaction tx = invocation.getArgument(0);
            tx.setId(UUID.randomUUID());
            tx.setCreatedAt(Instant.now());
            return tx;
        });

        EarnPointsResponse response = earnService.earnPoints(customerId, request);

        assertThat(response.points()).isEqualTo(5_000);
        assertThat(response.remainingBalance()).isEqualTo(1_005_000);
    }
}
