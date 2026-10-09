package com.example.customerrewards.dynamodb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * DynamoDB configuration using AWS SDK v2 Enhanced Client.
 *
 * Uses the default credential provider chain:
 * 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * 2. System properties (aws.accessKeyId, aws.secretAccessKey)
 * 3. Credential profiles in ~/.aws/credentials (typically used in Lambda)
 * 4. EC2 instance metadata (if running on EC2)
 * 5. Container credentials (if running in ECS)
 *
 * Region defaults to us-east-1 but can be overridden via:
 * - Environment variable: AWS_REGION
 * - Property: aws.region
 *
 * Endpoint can be overridden via aws.dynamodb.endpoint for local development
 * (e.g., http://localhost:8000 for DynamoDB Local).
 */
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.dynamodb.endpoint:}")
    private String dynamoDbEndpoint;

    /**
     * Creates a DynamoDbClient with:
     * - Default credential provider (no hardcoded keys)
     * - Configurable region (default us-east-1)
     * - Optional endpoint override for local testing
     */
    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create());

        // Override endpoint for local development (e.g., DynamoDB Local or LocalStack)
        Optional.ofNullable(dynamoDbEndpoint)
                .filter(endpoint -> !endpoint.isEmpty())
                .ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));

        return builder.build();
    }

    /**
     * Creates the Enhanced Client for high-level operations.
     * The Enhanced Client wraps DynamoDbClient and provides table-based APIs.
     */
    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }
}

