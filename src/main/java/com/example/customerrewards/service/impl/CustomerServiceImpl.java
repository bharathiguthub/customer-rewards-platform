package com.example.customerrewards.service.impl;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dto.response.TransactionHistoryResponse;
import com.example.customerrewards.dto.response.TransactionResponse;
import com.example.customerrewards.dynamodb.exception.DynamoDbException;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.DuplicateCustomerEmailException;
import com.example.customerrewards.mapper.CustomerMapper;
import com.example.customerrewards.mapper.RewardTransactionMapper;
import com.example.customerrewards.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

    private final DynamoCustomerRepository dynamoCustomerRepository;
    private final DynamoRewardTransactionRepository dynamoTransactionRepository;

    public CustomerServiceImpl(DynamoCustomerRepository dynamoCustomerRepository,
                                DynamoRewardTransactionRepository dynamoTransactionRepository) {
        this.dynamoCustomerRepository = dynamoCustomerRepository;
        this.dynamoTransactionRepository = dynamoTransactionRepository;
    }

    @Override
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        // Check DynamoDB for duplicate email
        if (dynamoCustomerRepository.existsByEmail(request.email())) {
            log.warn("Duplicate email registration attempt for: {}", request.email());
            throw new DuplicateCustomerEmailException(request.email());
        }
        
        try {
            // Create in DynamoDB (primary source)
            String customerId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            DynamoCustomer customer = new DynamoCustomer(
                customerId,
                request.firstName(),
                request.lastName(),
                request.email(),
                0,
                1,
                now,
                now
            );
            DynamoCustomer saved = dynamoCustomerRepository.save(customer);
            log.info("Customer created with id: {}", saved.getCustomerId());
            
            return CustomerMapper.toDynamoResponse(saved);
        } catch (DynamoDbException e) {
            if (e.getMessage().contains("email")) {
                throw new DuplicateCustomerEmailException(request.email());
            }
            throw e;
        }
    }

    @Override
    public CustomerResponse getCustomer(UUID customerId) {
        // Read from DynamoDB (primary source)
        return dynamoCustomerRepository.findById(customerId.toString())
                .map(c -> {
                    log.info("Customer retrieved with id: {}", customerId);
                    return CustomerMapper.toDynamoResponse(c);
                })
                .orElseThrow(() -> {
                    log.warn("Customer not found with id: {}", customerId);
                    return new CustomerNotFoundException(customerId);
                });
    }

    @Override
    public RewardBalanceResponse getRewardBalance(UUID customerId) {
        // Read from DynamoDB (primary source)
        return dynamoCustomerRepository.findById(customerId.toString())
                .map(c -> {
                    log.info("Balance retrieved for customer id: {}", customerId);
                    return new RewardBalanceResponse(customerId, c.getRewardBalance());
                })
                .orElseThrow(() -> {
                    log.warn("Customer not found for balance check, id: {}", customerId);
                    return new CustomerNotFoundException(customerId);
                });
    }

    @Override
    public TransactionHistoryResponse getTransactionHistory(UUID customerId, int page, int size) {
        // Verify customer exists
        if (dynamoCustomerRepository.findById(customerId.toString()).isEmpty()) {
            log.warn("Customer not found for transaction history, id: {}", customerId);
            throw new CustomerNotFoundException(customerId);
        }
        
        // Query from DynamoDB (primary source)
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DynamoRewardTransaction> transactions = dynamoTransactionRepository
                .findByCustomerOrderByCreatedAtDesc(customerId.toString(), pageRequest);
        
        List<TransactionResponse> transactionResponses = transactions
                .getContent()
                .stream()
                .map(RewardTransactionMapper::toDynamoResponse)
                .toList();
        
        return new TransactionHistoryResponse(
                customerId,
                transactionResponses,
                page,
                size,
                (int) transactions.getTotalElements(),
                transactions.getTotalPages()
        );
    }
}
