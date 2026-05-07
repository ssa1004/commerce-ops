CREATE TABLE payments (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    amount        NUMERIC(15,2) NOT NULL CHECK (amount >= 0),
    status        VARCHAR(20)  NOT NULL,
    external_ref  VARCHAR(64),
    failure_reason VARCHAR(255),
    attempts      INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_status ON payments (status);
