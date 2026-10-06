package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.RedeemPointsRequest;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import com.example.customerrewards.entity.TransactionType;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.InsufficientRewardBalanceException;
import com.example.customerrewards.exception.InvalidRewardPointsException;
import com.example.customerrewards.exception.RewardConcurrencyException;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
import com.example.customerrewards.service.RedemptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class RedemptionServiceImpl implements RedemptionService {

    private static final Logger log = LoggerFactory.getLogger(RedemptionServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final RewardTransactionRepository rewardTransactionRepository;

    public RedemptionServiceImpl(CustomerRepository customerRepository,
                                  RewardTransactionRepository rewardTransactionRepository) {
        this.customerRepository = customerRepository;
        this.rewardTransactionRepository = rewardTransactionRepository;
    }

    @Override
    @Transactional
    public RedeemPointsResponse redeemPoints(UUID customerId, String idempotencyKey, RedeemPointsRequest request) {
        // Step 1: Validate points > 0
        if (request.points() <= 0) {
            throw new InvalidRewardPointsException(request.points());
        }

        // Step 2: Load customer
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new CustomerNotFoundException(customerId));

        // Step 3: Idempotency check
        Optional<RewardTransaction> existingTx =
            rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existingTx.isPresent()) {
            log.info("Idempotent replay detected for key: {}", idempotencyKey);
            return RewardTransactionMapper.toRedeemResponse(existingTx.get(), customer.getRewardBalance());
        }

        // Step 4: Validate balance
        if (customer.getRewardBalance() < request.points()) {
            throw new InsufficientRewardBalanceException(request.points(), customer.getRewardBalance());
        }

        // Step 5: Deduct points
        customer.setRewardBalance(customer.getRewardBalance() - request.points());

        // Step 6: Save customer and flush so the optimistic lock version check
        // happens inside this method body (not deferred to commit time).
        // saveAndFlush forces the UPDATE immediately; if another transaction
        // committed a version bump first, ObjectOptimisticLockingFailureException
        // is raised here and we map it to a retryable domain exception.
        try {
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RewardConcurrencyException(customerId, e);
        }

        // Step 7 & 8: Create and save transaction
        RewardTransaction transaction = new RewardTransaction();
        transaction.setCustomer(customer);
        transaction.setType(TransactionType.REDEEM);
        transaction.setPoints(request.points());
        transaction.setIdempotencyKey(idempotencyKey);
        rewardTransactionRepository.save(transaction);

        log.info("Points redeemed: {} points for customer: {}, remaining balance: {}",
            request.points(), customerId, customer.getRewardBalance());

        return RewardTransactionMapper.toRedeemResponse(transaction, customer.getRewardBalance());
    }
}
