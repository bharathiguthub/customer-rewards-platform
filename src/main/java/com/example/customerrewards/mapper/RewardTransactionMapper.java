package com.example.customerrewards.mapper;

import com.example.customerrewards.dto.response.EarnPointsResponse;
import com.example.customerrewards.dto.response.RedeemPointsResponse;
import com.example.customerrewards.dto.response.TransactionResponse;
import com.example.customerrewards.dynamodb.models.DynamoRewardTransaction;

import java.util.UUID;

public class RewardTransactionMapper {

    private RewardTransactionMapper() {
        // Utility class
    }

    /**
     * Maps a DynamoDB RewardTransaction to a TransactionResponse.
     */
    public static TransactionResponse toDynamoResponse(DynamoRewardTransaction tx) {
        return new TransactionResponse(
            UUID.fromString(tx.getTransactionId()),
            tx.getTransactionType(),
            tx.getPoints(),
            null,
            tx.getCreatedAt()
        );
    }

    /**
     * Maps a DynamoDB RewardTransaction to a RedeemPointsResponse.
     */
    public static RedeemPointsResponse toDynamoRedeemResponse(DynamoRewardTransaction tx) {
        return new RedeemPointsResponse(
            UUID.fromString(tx.getTransactionId()),
            UUID.fromString(tx.getCustomerId()),
            tx.getPoints(),
            tx.getRemainingBalanceSnapshot(),
            tx.getIdempotencyKey(),
            tx.getCreatedAt()
        );
    }

    /**
     * Maps a DynamoDB RewardTransaction to an EarnPointsResponse.
     */
    public static EarnPointsResponse toDynamoEarnResponse(DynamoRewardTransaction tx) {
        return new EarnPointsResponse(
            UUID.fromString(tx.getTransactionId()),
            UUID.fromString(tx.getCustomerId()),
            tx.getPoints(),
            tx.getRemainingBalanceSnapshot(),
            tx.getCreatedAt()
        );
    }
}
