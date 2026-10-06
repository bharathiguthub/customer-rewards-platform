package com.example.customerrewards.dto.response;

import java.util.UUID;

public record RewardBalanceResponse(
    UUID customerId,
    int rewardBalance
) {}
