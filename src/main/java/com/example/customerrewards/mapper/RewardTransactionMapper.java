package com.example.customerrewards.mapper;

import com.example.customerrewards.dto.response.EarnPointsResponse;
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

    /**
     * Maps a RewardTransaction to a RedeemPointsResponse using the stored
     * {@code remainingBalanceSnapshot}. This snapshot was recorded at original commit
     * time, so idempotent replays always return the exact original result regardless
     * of any subsequent balance changes.
     *
     * <p>If the snapshot is null (legacy rows predating this column), the remaining
     * balance is returned as -1 as a sentinel. Phase 4 controller advice will map
     * this to a recoverable error if it ever surfaces.
     */
    public static RedeemPointsResponse toRedeemResponse(RewardTransaction tx) {
        int remainingBalance = (tx.getRemainingBalanceSnapshot() != null)
                ? tx.getRemainingBalanceSnapshot()
                : -1;  // sentinel for pre-snapshot legacy rows
        return new RedeemPointsResponse(
            tx.getId(),
            tx.getCustomer().getId(),
            tx.getPoints(),
            remainingBalance,
            tx.getIdempotencyKey(),
            tx.getCreatedAt()
        );
    }

    /**
     * Maps a RewardTransaction to an EarnPointsResponse.
     * Returns the remaining balance snapshot captured at the time of the earn operation.
     */
    public static EarnPointsResponse toEarnResponse(RewardTransaction tx) {
        int remainingBalance = (tx.getRemainingBalanceSnapshot() != null)
                ? tx.getRemainingBalanceSnapshot()
                : -1;  // sentinel for pre-snapshot legacy rows
        return new EarnPointsResponse(
            tx.getId(),
            tx.getCustomer().getId(),
            tx.getPoints(),
            remainingBalance,
            tx.getCreatedAt()
        );
    }
}
