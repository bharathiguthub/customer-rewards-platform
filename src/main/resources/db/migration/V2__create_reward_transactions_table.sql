CREATE TABLE reward_transactions (
    transaction_id  UUID         NOT NULL,
    customer_id     UUID         NOT NULL,
    type            VARCHAR(10)  NOT NULL,
    points          INT          NOT NULL,
    idempotency_key VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_reward_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT fk_reward_transactions_customer
        FOREIGN KEY (customer_id) REFERENCES customers(customer_id) ON DELETE CASCADE,
    CONSTRAINT chk_reward_transactions_points_positive CHECK (points > 0),
    CONSTRAINT chk_reward_transactions_type CHECK (type IN ('EARN', 'REDEEM'))
);

CREATE UNIQUE INDEX uq_reward_transactions_customer_idempotency_key
    ON reward_transactions (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
