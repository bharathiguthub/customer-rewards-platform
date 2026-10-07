package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.EarnPointsRequest;
import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import com.example.customerrewards.entity.TransactionType;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
import com.example.customerrewards.service.EarnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EarnServiceImpl implements EarnService {

    private static final Logger log = LoggerFactory.getLogger(EarnServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final RewardTransactionRepository rewardTransactionRepository;

    public EarnServiceImpl(CustomerRepository customerRepository,
                          RewardTransactionRepository rewardTransactionRepository) {
        this.customerRepository = customerRepository;
        this.rewardTransactionRepository = rewardTransactionRepository;
    }

    @Override
    @Transactional
    public EarnPointsResponse earnPoints(UUID customerId, EarnPointsRequest request) {

        // Step 1: Validate points > 0.
        // Done before any DB access so invalid requests are rejected immediately.
        if (request.points() <= 0) {
            throw new InvalidRewardPointsException(request.points());
        }

        // Step 2: Load customer.
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        // Step 3: Check for integer overflow before adding.
        // Prevent: customer.rewardBalance (int) + request.points() (int) from exceeding Integer.MAX_VALUE
        if (customer.getRewardBalance() > Integer.MAX_VALUE - request.points()) {
            throw new IllegalArgumentException(
                    "Adding " + request.points() + " points would exceed maximum reward balance");
        }

        // Step 4: Increment points.
        customer.setRewardBalance(customer.getRewardBalance() + request.points());

        // Step 5: Flush the customer update immediately so the optimistic lock version
        // check fires inside this method body (not deferred to commit time).
        // If another transaction already committed a version bump for this customer,
        // Hibernate raises ObjectOptimisticLockingFailureException here and we map
        // it to a retryable domain exception. The caller receives a clear 409 signal.
        try {
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RewardConcurrencyException(customerId, e);
        }

        // Step 6: Persist the transaction with a balance snapshot.
        // The snapshot captures the exact remaining balance at this moment.
        RewardTransaction transaction = new RewardTransaction();
        transaction.setCustomer(customer);
        transaction.setType(TransactionType.EARN);
        transaction.setPoints(request.points());
        transaction.setRemainingBalanceSnapshot(customer.getRewardBalance());

        rewardTransactionRepository.saveAndFlush(transaction);

        log.info("Points earned: {} for customer: {}, remaining balance: {}",
                request.points(), customerId, customer.getRewardBalance());

        return RewardTransactionMapper.toEarnResponse(transaction);
    }
}
