package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dto.response.TransactionHistoryResponse;
import com.example.customerrewards.dto.response.TransactionResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.DuplicateCustomerEmailException;
import com.example.customerrewards.mapper.CustomerMapper;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
import com.example.customerrewards.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final CustomerRepository customerRepository;
    private final RewardTransactionRepository rewardTransactionRepository;

    public CustomerServiceImpl(CustomerRepository customerRepository,
                                RewardTransactionRepository rewardTransactionRepository) {
        this.customerRepository = customerRepository;
        this.rewardTransactionRepository = rewardTransactionRepository;
    }

    @Override
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            log.warn("Duplicate email registration attempt for: {}", request.email());
            throw new DuplicateCustomerEmailException(request.email());
        }
        Customer customer = new Customer();
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setRewardBalance(0);
        Customer saved = customerRepository.save(customer);
        log.info("Customer created with id: {}", saved.getId());
        return CustomerMapper.toResponse(saved);
    }

    @Override
    public CustomerResponse getCustomer(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> {
                log.warn("Customer not found with id: {}", customerId);
                return new CustomerNotFoundException(customerId);
            });
        log.info("Customer retrieved with id: {}", customerId);
        return CustomerMapper.toResponse(customer);
    }

    @Override
    public RewardBalanceResponse getRewardBalance(UUID customerId) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> {
                log.warn("Customer not found for balance check, id: {}", customerId);
                return new CustomerNotFoundException(customerId);
            });
        log.info("Balance retrieved for customer id: {}", customerId);
        return CustomerMapper.toBalanceResponse(customer);
    }

    @Override
    public TransactionHistoryResponse getTransactionHistory(UUID customerId, int page, int size) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> {
                log.warn("Customer not found for transaction history, id: {}", customerId);
                return new CustomerNotFoundException(customerId);
            });
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<com.example.customerrewards.entity.RewardTransaction> txPage =
            rewardTransactionRepository.findByCustomerOrderByCreatedAtDesc(customer, pageRequest);
        List<TransactionResponse> transactions = txPage.getContent().stream()
            .map(RewardTransactionMapper::toResponse)
            .toList();
        return new TransactionHistoryResponse(
            customerId,
            transactions,
            txPage.getNumber(),
            txPage.getSize(),
            txPage.getTotalElements(),
            txPage.getTotalPages()
        );
    }
}
