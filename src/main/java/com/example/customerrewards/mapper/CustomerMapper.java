package com.example.customerrewards.mapper;

import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;

import java.util.UUID;

public class CustomerMapper {

    private CustomerMapper() {
        // Utility class
    }

    public static CustomerResponse toDynamoResponse(DynamoCustomer customer) {
        return new CustomerResponse(
            UUID.fromString(customer.getCustomerId()),
            customer.getFirstName(),
            customer.getLastName(),
            customer.getEmail(),
            customer.getRewardBalance(),
            customer.getCreatedAt()
        );
    }

    public static RewardBalanceResponse toDynamoBalanceResponse(DynamoCustomer customer) {
        return new RewardBalanceResponse(
            UUID.fromString(customer.getCustomerId()),
            customer.getRewardBalance()
        );
    }
}
