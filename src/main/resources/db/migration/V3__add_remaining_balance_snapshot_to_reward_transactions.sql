-- Adds a balance snapshot column to reward_transactions so idempotent redemption
-- replays can return the exact same remainingBalance as the original response.
-- Nullable to be backward-compatible with existing rows (admin credits, legacy data).
ALTER TABLE reward_transactions
    ADD COLUMN remaining_balance_snapshot INT NULL;
