package com.example.customerrewards.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
    UUID customerId,
    String firstName,
    String lastName,
    String email,
    int rewardBalance,
    Instant createdAt
) {}
