package com.example.customerrewards.service;

import com.example.customerrewards.dto.request.CreateCustomerRequest;
import com.example.customerrewards.dto.response.CustomerResponse;
import com.example.customerrewards.dto.response.RewardBalanceResponse;
import com.example.customerrewards.entity.Customer;
import com.example.customerrewards.exception.CustomerNotFoundException;
import com.example.customerrewards.exception.DuplicateCustomerEmailException;
import com.example.customerrewards.repository.CustomerRepository;
import com.example.customerrewards.repository.RewardTransactionRepository;
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
    private CustomerRepository customerRepository;

    @Mock
    private RewardTransactionRepository rewardTransactionRepository;

    @InjectMocks
    private CustomerServiceImpl customerService;

    private Customer buildCustomer(UUID id, String email) {
        Customer c = new Customer();
        c.setId(id);
        c.setFirstName("Alice");
        c.setLastName("Nguyen");
        c.setEmail(email);
        c.setRewardBalance(0);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return c;
    }

    @Test
    void createCustomer_success() {
        UUID id = UUID.randomUUID();
        String email = "alice@example.com";
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", email);
        Customer saved = buildCustomer(id, email);

        when(customerRepository.existsByEmail(email)).thenReturn(false);
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);

        CustomerResponse response = customerService.createCustomer(request);

        assertThat(response.customerId()).isEqualTo(id);
        assertThat(response.email()).isEqualTo(email);
        assertThat(response.rewardBalance()).isEqualTo(0);
    }

    @Test
    void createCustomer_duplicateEmail_throwsDuplicateCustomerEmailException() {
        CreateCustomerRequest request = new CreateCustomerRequest("Alice", "Nguyen", "dup@example.com");
        when(customerRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(DuplicateCustomerEmailException.class, () -> customerService.createCustomer(request));
    }

    @Test
    void getCustomer_found_returnsCustomerResponse() {
        UUID id = UUID.randomUUID();
        Customer customer = buildCustomer(id, "found@example.com");
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCustomer(id);

        assertThat(response.customerId()).isEqualTo(id);
        assertThat(response.email()).isEqualTo("found@example.com");
    }

    @Test
    void getCustomer_notFound_throwsCustomerNotFoundException() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> customerService.getCustomer(id));
    }

    @Test
    void getRewardBalance_found_returnsBalance() {
        UUID id = UUID.randomUUID();
        Customer customer = buildCustomer(id, "balance@example.com");
        customer.setRewardBalance(500);
        when(customerRepository.findById(id)).thenReturn(Optional.of(customer));

        RewardBalanceResponse response = customerService.getRewardBalance(id);

        assertThat(response.customerId()).isEqualTo(id);
        assertThat(response.rewardBalance()).isEqualTo(500);
    }

    @Test
    void getRewardBalance_notFound_throwsCustomerNotFoundException() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> customerService.getRewardBalance(id));
    }
}
