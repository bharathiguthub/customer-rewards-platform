package com.example.customerrewards.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID transactionId,
    String type,
    int points,
    String description,
    Instant createdAt
) {}
