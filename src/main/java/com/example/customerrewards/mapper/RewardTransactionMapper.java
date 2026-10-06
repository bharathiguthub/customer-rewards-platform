package com.example.customerrewards.mapper;

import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dto.response.TransactionResponse;
import com.example.customerrewards.entity.RewardTransaction;

public class RewardTransactionMapper {

    private RewardTransactionMapper() {
        // Utility class
    }

    public static TransactionResponse toResponse(RewardTransaction tx) {
        return new TransactionResponse(
            tx.getId(),
            tx.getType().name(),
            tx.getPoints(),
            null,  // description field not available until Phase 4
            tx.getCreatedAt()
        );
    }

    public static RedeemPointsResponse toRedeemResponse(RewardTransaction tx, int remainingBalance) {
        return new RedeemPointsResponse(
            tx.getId(),
            tx.getCustomer().getId(),
            tx.getPoints(),
            remainingBalance,
            tx.getIdempotencyKey(),
            tx.getCreatedAt()
        );
    }
}
