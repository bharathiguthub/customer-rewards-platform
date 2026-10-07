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
import org.springframework.dao.DataIntegrityViolationException;
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

        // Step 1: Validate points > 0.
        // Done before any DB access so invalid requests are rejected immediately.
        if (request.points() <= 0) {
            throw new InvalidRewardPointsException(request.points());
        }

        // Step 2: Idempotency check — before loading the customer.
        // If a transaction already exists for this (customerId, idempotencyKey) pair,
        // return its stored remainingBalanceSnapshot directly. No customer load is
        // needed because all the information required for the response is on the
        // transaction itself. This also means the customer entity is never locked
        // on the replay path, keeping replay throughput independent of new redemptions.
        Optional<RewardTransaction> existingTx =
                rewardTransactionRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (existingTx.isPresent()) {
            log.info("Idempotent replay for key: {} customer: {}", idempotencyKey, customerId);
            // toRedeemResponse reads tx.getRemainingBalanceSnapshot() — the exact value
            // stored at original commit time — so replays always return the original result
            // regardless of any subsequent balance changes.
            return RewardTransactionMapper.toRedeemResponse(existingTx.get());
        }

        // Step 3: Load customer — only reached for genuinely new redemptions.
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        // Step 4: Validate balance.
        if (customer.getRewardBalance() < request.points()) {
            throw new InsufficientRewardBalanceException(request.points(), customer.getRewardBalance());
        }

        // Step 5: Deduct points.
        customer.setRewardBalance(customer.getRewardBalance() - request.points());

        // Step 6: Flush the customer update immediately so the optimistic lock version
        // check fires inside this method body (not deferred to commit time).
        // If another transaction already committed a version bump for this customer,
        // Hibernate raises ObjectOptimisticLockingFailureException here and we map
        // it to a retryable domain exception. The caller receives a clear 409 signal.
        try {
            customerRepository.saveAndFlush(customer);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RewardConcurrencyException(customerId, e);
        }

        // Step 7: Persist the transaction with a balance snapshot.
        // The snapshot captures the exact remaining balance at this moment so any
        // future idempotent replay returns the original result, not the current balance.
        //
        // Race condition: two requests carrying the SAME idempotency key may both pass
        // the idempotency check above before either has committed (the check ran before
        // either INSERT existed). In that case one INSERT will succeed and the other
        // will hit the partial unique index on (customer_id, idempotency_key), surfacing
        // as a DataIntegrityViolationException. We catch that here and re-read the
        // winning transaction, returning its stored snapshot as the idempotent response.
        // This guarantees points are deducted at most once even under concurrent same-key
        // requests, and it never exposes an unhandled constraint exception to the caller.
        RewardTransaction transaction = new RewardTransaction();
        transaction.setCustomer(customer);
        transaction.setType(TransactionType.REDEEM);
        transaction.setPoints(request.points());
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setRemainingBalanceSnapshot(customer.getRewardBalance());

        try {
            rewardTransactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent request with the same idempotency key committed first.
            // Re-read the winning transaction and return its stored snapshot.
            log.warn("Concurrent same-key insert detected for key: {}, reading winner", idempotencyKey);
            RewardTransaction winner = rewardTransactionRepository
                    .findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency constraint violated but transaction not found for key: " + idempotencyKey));
            return RewardTransactionMapper.toRedeemResponse(winner);
        }

        log.info("Points redeemed: {} for customer: {}, remaining balance: {}",
                request.points(), customerId, customer.getRewardBalance());

        return RewardTransactionMapper.toRedeemResponse(transaction);
    }
}
