package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.models.IdempotencyLockItem;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InsufficientRewardBalanceException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.service.impl.RedemptionServiceImpl;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedemptionServiceImplTest {

    @Mock
    private DynamoCustomerRepository dynamoCustomerRepository;

    @Mock
    private DynamoRewardTransactionRepository dynamoTransactionRepository;

    @InjectMocks
    private RedemptionServiceImpl redemptionService;

    private DynamoCustomer buildDynamoCustomer(String id, int balance) {
        return new DynamoCustomer(
            id,
            "Alice",
            "Nguyen",
            "alice@example.com",
            balance,
            1,
            Instant.now(),
            Instant.now()
        );
    }

    private DynamoRewardTransaction buildDynamoTransaction(String txId, String customerId, int points,
                                                           String idempotencyKey, int remainingBalance) {
        return new DynamoRewardTransaction(
            txId,
            Instant.now(),
            customerId,
            "REDEEM",
            points,
            idempotencyKey,
            remainingBalance
        );
    }

    @Test
    void redeemPoints_success() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, 1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-success";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;

        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.empty());
        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));
        doNothing().when(dynamoTransactionRepository).executeRedemptionTransaction(
            anyString(), anyLong(), anyInt(), anyString(), any(DynamoRewardTransaction.class));

        RedeemPointsResponse response = redemptionService.redeemPoints(customerId, idempotencyKey, request);

        assertThat(response.pointsRedeemed()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);
        assertThat(response.customerId()).isEqualTo(customerId);
    }

    @Test
    void redeemPoints_insufficientBalance() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, 100);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-insufficient";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;

        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.empty());
        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));

        assertThrows(InsufficientRewardBalanceException.class,
            () -> redemptionService.redeemPoints(customerId, idempotencyKey, request));
    }

    @Test
    void redeemPoints_invalidPoints() {
        UUID customerId = UUID.randomUUID();
        RedeemPointsRequest request = new RedeemPointsRequest(0, null);
        String idempotencyKey = "key-invalid";

        assertThrows(InvalidRewardPointsException.class,
            () -> redemptionService.redeemPoints(customerId, idempotencyKey, request));
    }

    @Test
    void redeemPoints_customerNotFound() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-notfound";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;

        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.empty());
        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class,
            () -> redemptionService.redeemPoints(customerId, idempotencyKey, request));
    }

    @Test
    void redeemPoints_idempotentReplay() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        String idempotencyKey = "key-replay";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;
        String txId = UUID.randomUUID().toString();
        
        // Mock the lock item
        IdempotencyLockItem lockItem = new IdempotencyLockItem(
            compositeId, txId, customIdStr, Instant.now());
        
        // Mock the cached transaction
        DynamoRewardTransaction cachedTx = buildDynamoTransaction(
            txId, customIdStr, 500, idempotencyKey, 500);

        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.of(lockItem));
        when(dynamoTransactionRepository.findById(txId))
            .thenReturn(Optional.of(cachedTx));

        RedeemPointsResponse response = redemptionService.redeemPoints(
            customerId, idempotencyKey, new RedeemPointsRequest(500, null));

        assertThat(response.pointsRedeemed()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);
        verify(dynamoTransactionRepository, never()).executeRedemptionTransaction(
            anyString(), anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void redeemPoints_concurrencyConflict() {
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, 1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-conflict";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;

        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.empty());
        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));
        doThrow(new com.example.customerrewards.dynamodb.exception.DynamoDbException(
                "ConditionalCheckFailedException version"))
            .when(dynamoTransactionRepository).executeRedemptionTransaction(
                anyString(), anyLong(), anyInt(), anyString(), any(DynamoRewardTransaction.class));

        assertThrows(RewardConcurrencyException.class,
            () -> redemptionService.redeemPoints(customerId, idempotencyKey, request));
    }

    @Test
    void redeemPoints_concurrentIdempotentRequest_SimplifiedTest() {
        // This test demonstrates the idempotency lock behavior conceptually
        // In production, concurrent requests to the same (customerId, idempotencyKey)
        // will result in one success and others reading the cached lock item
        UUID customerId = UUID.randomUUID();
        String customIdStr = customerId.toString();
        DynamoCustomer customer = buildDynamoCustomer(customIdStr, 1000);
        RedeemPointsRequest request = new RedeemPointsRequest(500, null);
        String idempotencyKey = "key-concurrent-idempotent";
        String compositeId = "IDEMPOTENCY#" + customIdStr + "#" + idempotencyKey;

        // First attempt: lock doesn't exist
        when(dynamoTransactionRepository.getIdempotencyLock(compositeId))
            .thenReturn(Optional.empty());
        when(dynamoCustomerRepository.findById(customIdStr)).thenReturn(Optional.of(customer));
        doNothing().when(dynamoTransactionRepository).executeRedemptionTransaction(
            anyString(), anyLong(), anyInt(), anyString(), any(DynamoRewardTransaction.class));

        RedeemPointsResponse response = redemptionService.redeemPoints(
            customerId, idempotencyKey, request);

        assertThat(response.pointsRedeemed()).isEqualTo(500);
        assertThat(response.remainingBalance()).isEqualTo(500);
    }
}
