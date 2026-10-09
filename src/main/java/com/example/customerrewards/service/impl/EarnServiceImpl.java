package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.service.EarnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class EarnServiceImpl implements EarnService {

    private static final Logger log = LoggerFactory.getLogger(EarnServiceImpl.class);

    private final DynamoCustomerRepository dynamoCustomerRepository;
    private final DynamoRewardTransactionRepository dynamoTransactionRepository;

    public EarnServiceImpl(DynamoCustomerRepository dynamoCustomerRepository,
                          DynamoRewardTransactionRepository dynamoTransactionRepository) {
        this.dynamoCustomerRepository = dynamoCustomerRepository;
        this.dynamoTransactionRepository = dynamoTransactionRepository;
    }

    @Override
    public EarnPointsResponse earnPoints(UUID customerId, EarnPointsRequest request) {

        // Step 1: Validate points > 0
        if (request.points() <= 0) {
            throw new InvalidRewardPointsException(request.points());
        }

        // Step 2: Load customer from DynamoDB (primary source)
        DynamoCustomer customer = dynamoCustomerRepository.findById(customerId.toString())
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        // Step 3: Check for integer overflow
        if (customer.getRewardBalance() > Integer.MAX_VALUE - request.points()) {
            throw new IllegalArgumentException(
                    "Adding " + request.points() + " points would exceed maximum reward balance");
        }

        // Step 4: Increment points
        customer.setRewardBalance(customer.getRewardBalance() + request.points());
        customer.setUpdatedAt(Instant.now());
        customer.setVersion(customer.getVersion() + 1);

        // Step 5: Update customer in DynamoDB with optimistic locking
        try {
            dynamoCustomerRepository.saveWithVersionCheck(customer, customer.getVersion() - 1);
            log.info("Customer updated in DynamoDB");
        } catch (DynamoDbException e) {
            if (e.getMessage().contains("ConditionalCheckFailedException")) {
                throw new RewardConcurrencyException(customerId, e);
            }
            throw e;
        }

        // Step 6: Create transaction object
        Instant now = Instant.now();
        String transactionId = UUID.randomUUID().toString();
        DynamoRewardTransaction transaction = new DynamoRewardTransaction(
                transactionId,
                now,
                customerId.toString(),
                "EARN",
                request.points(),
                null,
                customer.getRewardBalance()
        );

        // Step 7: Persist transaction in DynamoDB
        dynamoTransactionRepository.save(transaction);
        log.info("Transaction saved in DynamoDB");

        log.info("Points earned: {} for customer: {}, remaining balance: {}",
                request.points(), customerId, customer.getRewardBalance());

        return RewardTransactionMapper.toDynamoEarnResponse(transaction);
    }
}
