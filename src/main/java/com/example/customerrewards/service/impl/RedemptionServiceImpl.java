package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.models.IdempotencyLockItem;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InsufficientRewardBalanceException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.service.RedemptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RedemptionServiceImpl implements RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionServiceImpl.class);

    private final DynamoCustomerRepository dynamoCustomerRepository;
    private final DynamoRewardTransactionRepository dynamoTransactionRepository;

    public RedemptionServiceImpl(DynamoCustomerRepository dynamoCustomerRepository,
                                DynamoRewardTransactionRepository dynamoTransactionRepository) {
        this.dynamoCustomerRepository = dynamoCustomerRepository;
        this.dynamoTransactionRepository = dynamoTransactionRepository;
    }

    @Override
    public RedeemPointsResponse redeemPoints(UUID customerId, String idempotencyKey, RedeemPointsRequest request) {
        // Step 1: Validate points > 0
        if (request.points() <= 0) {
            throw new InvalidRewardPointsException(request.points());
        }

        // Step 2: Idempotency check — check if lock already exists from a previous successful request
        String compositeIdempotencyId = "IDEMPOTENCY#" + customerId + "#" + idempotencyKey;
        Optional<IdempotencyLockItem> existingLock = dynamoTransactionRepository.getIdempotencyLock(compositeIdempotencyId);
        if (existingLock.isPresent()) {
            log.info("Idempotent replay for key: {} customer: {}, returning cached result", idempotencyKey, customerId);
            // Retrieve the cached transaction result using transactionId from lock
            String cachedTransactionId = existingLock.get().getTransactionId();
            DynamoRewardTransaction cachedTx = dynamoTransactionRepository.findById(cachedTransactionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency lock found but transaction not found for transactionId: " + cachedTransactionId));
            return RewardTransactionMapper.toDynamoRedeemResponse(cachedTx);
        }

        // Step 3: Load customer from DynamoDB (primary source)
        DynamoCustomer customer = dynamoCustomerRepository.findById(customerId.toString())
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        // Step 4: Validate balance
        if (customer.getRewardBalance() < request.points()) {
            throw new InsufficientRewardBalanceException(request.points(), customer.getRewardBalance());
        }

        // Step 5: Create transaction object (to be inserted atomically with customer update and lock)
        Instant now = Instant.now();
        String transactionId = UUID.randomUUID().toString();
        DynamoRewardTransaction transaction = new DynamoRewardTransaction(
                transactionId,
                now,
                customerId.toString(),
                "REDEEM",
                request.points(),
                idempotencyKey,
                customer.getRewardBalance() - request.points()
        );

        // Step 6: Execute atomic redemption using transactWriteItems
        // This atomically:
        // 1. Updates customer balance and version
        // 2. Creates transaction record
        // 3. Creates idempotency lock (prevents concurrent duplicate processing)
        try {
            dynamoTransactionRepository.executeRedemptionTransaction(
                    customerId.toString(),
                    customer.getVersion(),
                    request.points(),
                    idempotencyKey,
                    transaction
            );
            log.info("Points redeemed: {} for customer: {}, remaining balance: {}, lockId: {}",
                    request.points(), customerId, customer.getRewardBalance() - request.points(), compositeIdempotencyId);
            return RewardTransactionMapper.toDynamoRedeemResponse(transaction);
        } catch (DynamoDbException e) {
            // Check if the failure was due to lock contention (idempotency conflict)
            if (e.getMessage().contains("ConditionalCheckFailedException") && e.getMessage().contains("lockId")) {
                // Lock creation failed because another concurrent request already claimed it
                log.warn("Concurrent same-key lock contention detected for key: {}, reading winner lock", idempotencyKey);
                Optional<IdempotencyLockItem> winnerLock = dynamoTransactionRepository.getIdempotencyLock(compositeIdempotencyId);
                if (winnerLock.isPresent()) {
                    String winnerTransactionId = winnerLock.get().getTransactionId();
                    DynamoRewardTransaction winnerTx = dynamoTransactionRepository.findById(winnerTransactionId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Idempotency lock found but transaction not found for transactionId: " + winnerTransactionId));
                    return RewardTransactionMapper.toDynamoRedeemResponse(winnerTx);
                } else {
                    throw new IllegalStateException("Idempotency lock condition failed but lock not found for key: " + compositeIdempotencyId);
                }
            } else if (e.getMessage().contains("ConditionalCheckFailedException")) {
                // Version mismatch - concurrent update to customer
                throw new RewardConcurrencyException(customerId, e);
            }
            throw e;
        }
    }
}
