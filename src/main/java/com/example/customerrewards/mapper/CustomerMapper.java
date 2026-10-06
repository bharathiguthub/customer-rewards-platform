package com.example.customerrewards.mapper;

import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.entity.Customer;

public class CustomerMapper {

    private CustomerMapper() {
        // Utility class
    }

    public static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
            customer.getId(),
            customer.getFirstName(),
            customer.getLastName(),
            customer.getEmail(),
            customer.getRewardBalance(),
            customer.getCreatedAt()
        );
    }

    public static RewardBalanceResponse toBalanceResponse(Customer customer) {
        return new RewardBalanceResponse(
            customer.getId(),
            customer.getRewardBalance()
        );
    }
}
