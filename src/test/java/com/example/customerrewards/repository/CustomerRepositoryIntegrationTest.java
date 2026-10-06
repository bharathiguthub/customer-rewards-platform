package com.example.customerrewards.repository;

import com.example.customerrewards.entity.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class CustomerRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CustomerRepository customerRepository;

    private Customer buildCustomer(String email) {
        Customer customer = new Customer();
        customer.setFirstName("Alice");
        customer.setLastName("Nguyen");
        customer.setEmail(email);
        customer.setRewardBalance(0);
        return customer;
    }

    @Test
    void givenValidCustomer_whenSave_thenPersisted() {
        Customer customer = buildCustomer("alice." + UUID.randomUUID() + "@example.com");

        customerRepository.save(customer);
        entityManager.flush();

        assertThat(customer.getId()).isNotNull();
        assertThat(customer.getEmail()).isEqualTo(customer.getEmail());
        assertThat(customer.getRewardBalance()).isEqualTo(0);
        assertThat(customer.getVersion()).isEqualTo(0L);
        assertThat(customer.getCreatedAt()).isNotNull();
    }

    @Test
    void givenDuplicateEmail_whenSave_thenThrowsDataIntegrityViolationException() {
        String sharedEmail = "duplicate." + UUID.randomUUID() + "@example.com";
        Customer first = buildCustomer(sharedEmail);
        customerRepository.saveAndFlush(first);

        assertThrows(DataIntegrityViolationException.class, () -> {
            Customer second = buildCustomer(sharedEmail);
            customerRepository.saveAndFlush(second);
        });
    }

    @Test
    void givenCustomer_whenFindByEmail_thenReturnsCustomer() {
        String email = "findme." + UUID.randomUUID() + "@example.com";
        Customer customer = buildCustomer(email);
        customerRepository.save(customer);
        entityManager.flush();

        Optional<Customer> found = customerRepository.findByEmail(email);

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo(email);
    }

    @Test
    void givenNegativeRewardBalance_whenSave_thenThrowsDataIntegrityViolationException() {
        assertThrows(DataIntegrityViolationException.class, () -> {
            Customer customer = buildCustomer("negative." + UUID.randomUUID() + "@example.com");
            customer.setRewardBalance(-1);
            customerRepository.saveAndFlush(customer);
        });
    }
}
