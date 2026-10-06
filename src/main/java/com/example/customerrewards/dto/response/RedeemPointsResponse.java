package com.example.customerrewards.dto.response;

import java.time.Instant;
import java.util.UUID;

public record RedeemPointsResponse(
    UUID transactionId,
    UUID customerId,
    int pointsRedeemed,
    int remainingBalance,
    String idempotencyKey,
    Instant createdAt
) {}
