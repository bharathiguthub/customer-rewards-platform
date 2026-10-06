package com.example.customerrewards.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RedeemPointsRequest(
    @Positive int points,
    @Size(max = 255) String description
) {}
