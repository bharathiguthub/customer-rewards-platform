package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.service.impl.EarnServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EarnServiceImplTest {

    @Mock
    private DynamoCustomerRepository dynamoCustomerRepository;

    @Mock
    private DynamoRewardTransactionRepository dynamoTransactionRepository;

    @InjectMocks
    private EarnServiceImpl earnService;

    private DynamoCustomer buildDynamoCustomer(String id, String email, int balance) {
        return new DynamoCustomer(
            id,
            "Alice",
            "Nguyen",
            email,
            balance,
            1,
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void testEarnPoints_Success() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, "alice@example.com", 500);
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));
        when(dynamoCustomerRepository.saveWithVersionCheck(any(DynamoCustomer.class), anyLong())).thenAnswer(invocation -> {
            DynamoCustomer c = invocation.getArgument(0);
            c.setVersion(2);
            return c;
        });
        when(dynamoTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EarnPointsResponse response = earnService.earnPoints(customerId, request);

        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.points()).isEqualTo(100);
        assertThat(response.remainingBalance()).isEqualTo(600);
    }

    @Test
    void testEarnPoints_LargeBalance() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, "alice@example.com", Integer.MAX_VALUE - 50);
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));

        assertThrows(IllegalArgumentException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_InvalidPoints() {
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(0);

        assertThrows(InvalidRewardPointsException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_CustomerNotFound() {
        UUID customerId = UUID.randomUUID();
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(dynamoCustomerRepository.findById(customerId.toString())).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> earnService.earnPoints(customerId, request));
    }

    @Test
    void testEarnPoints_ConcurrencyConflict() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, "alice@example.com", 500);
        EarnPointsRequest request = new EarnPointsRequest(100);

        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));
        when(dynamoCustomerRepository.saveWithVersionCheck(any(DynamoCustomer.class), anyLong()))
            .thenThrow(new com.example.customerrewards.dynamodb.exception.DynamoDbException(
                "ConditionalCheckFailedException"));

        assertThrows(RewardConcurrencyException.class, () -> earnService.earnPoints(customerId, request));
    }
}
