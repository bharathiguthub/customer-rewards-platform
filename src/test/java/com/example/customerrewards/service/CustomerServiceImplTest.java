package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.dynamodb.models.DynamoCustomer;
import com.example.customerrewards.dynamodb.repository.DynamoCustomerRepository;
import com.example.customerrewards.dynamodb.repository.DynamoRewardTransactionRepository;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.DuplicateCustomerEmailException;
import com.example.customerrewards.service.impl.CustomerServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private DynamoCustomerRepository dynamoCustomerRepository;

    @Mock
    private DynamoRewardTransactionRepository dynamoTransactionRepository;

    @InjectMocks
    private CustomerServiceImpl customerService;

    private DynamoCustomer buildDynamoCustomer(String id, String email) {
        return new DynamoCustomer(
            id,
            "Alice",
            "Nguyen",
            email,
            0,
            1,
            Instant.now(),
            Instant.now()
        );
    }

    @Test
    void createCustomer_success() {
        String id = UUID.randomUUID().toString();
        String email = "alice@example.com";
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", email);
        DynamoCustomer saved = buildDynamoCustomer(id, email);

        when(dynamoCustomerRepository.existsByEmail(email)).thenReturn(false);
        when(dynamoCustomerRepository.save(any(DynamoCustomer.class))).thenReturn(saved);

        CustomerResponse response = customerService.createCustomer(request);

        assertThat(response.customerId()).isEqualTo(UUID.fromString(id));
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.rewardBalance()).isEqualTo(0);
    }

    @Test
    void createCustomer_duplicateEmail_throwsDuplicateCustomerEmailException() {
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", "dup@example.com");
        when(dynamoCustomerRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(DuplicateCustomerEmailException.class, () -> customerService.createCustomer(request));
    }

    @Test
    void getCustomer_found_returnsCustomerResponse() {
        UUID id = UUID.randomUUID();
        DynamoCustomer customer = buildDynamoCustomer(id.toString(), "found@example.com");
        when(dynamoCustomerRepository.findById(id.toString())).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCustomer(id);

        assertThat(response.customerId()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("found@example.com");
    }

    @Test
    void getCustomer_notFound_throwsCustomerNotFoundException() {
        UUID id = UUID.randomUUID();
        when(dynamoCustomerRepository.findById(id.toString())).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> customerService.getCustomer(id));
    }

    @Test
    void getRewardBalance_found_returnsBalance() {
        UUID id = UUID.randomUUID();
        DynamoCustomer customer = buildDynamoCustomer(id.toString(), "test@example.com");
        customer.setRewardBalance(1000);
        when(dynamoCustomerRepository.findById(id.toString())).thenReturn(Optional.of(customer));

        RewardBalanceResponse response = customerService.getRewardBalance(id);

        assertThat(response.customerId()).isEqualTo(id);
        assertThat(response.rewardBalance()).isEqualTo(1000);
    }

    @Test
    void getRewardBalance_notFound_throwsCustomerNotFoundException() {
        UUID id = UUID.randomUUID();
        when(dynamoCustomerRepository.findById(id.toString())).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> customerService.getRewardBalance(id));
    }
}
