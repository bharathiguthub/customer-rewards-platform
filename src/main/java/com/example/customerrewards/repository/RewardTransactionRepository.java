package com.example.customerrewards.repository;

import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.entity.RewardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RewardTransactionRepository extends JpaRepository<RewardTransaction, UUID> {
    List<RewardTransaction> findByCustomerOrderByCreatedAtDesc(Customer customer);
    Optional<RewardTransaction> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);
    Page<RewardTransaction> findByCustomerOrderByCreatedAtDesc(Customer customer, Pageable pageable);
}
