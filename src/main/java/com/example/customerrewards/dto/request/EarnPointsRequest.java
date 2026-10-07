package com.example.customerrewards.dto.request;

import jakarta.validation.constraints.Positive;

public record EarnPointsRequest(
    @Positive int points
) {}
