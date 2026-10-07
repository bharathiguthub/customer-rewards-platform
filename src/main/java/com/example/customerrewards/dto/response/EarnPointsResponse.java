package com.example.customerrewards.dto.response;

import java.time.Instant;
import java.util.UUID;

public record EarnPointsResponse(
    UUID transactionId,
    UUID customerId,
    int points,
    int remainingBalance,
    Instant createdAt
) {}
