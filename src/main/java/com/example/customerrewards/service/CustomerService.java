package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dto.response.TransactionHistoryResponse;

import java.util.UUID;

public interface CustomerService {
    CustomerResponse createCustomer(CreateCustomerRequest request);
    CustomerResponse getCustomer(UUID customerId);
    RewardBalanceResponse getRewardBalance(UUID customerId);
    TransactionHistoryResponse getTransactionHistory(UUID customerId, int page, int size);
}
