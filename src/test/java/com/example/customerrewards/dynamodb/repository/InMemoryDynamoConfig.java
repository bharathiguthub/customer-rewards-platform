package com.example.customerrewards.dynamodb.repository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Test configuration that provides in-memory implementations of the DynamoDB repositories
 * when running with 'test' profile. This avoids calls to real DynamoDB during integration tests.
 */
@Configuration
@Profile("test")
public class InMemoryDynamoConfig {

    @Bean
    @Primary
    public DynamoCustomerRepository inMemoryCustomerRepository() {
        return new InMemoryDynamoCustomerRepository();
    }

    @Bean
    @Primary
    public DynamoRewardTransactionRepository inMemoryTransactionRepository() {
        return new InMemoryDynamoRewardTransactionRepository();
    }
}
