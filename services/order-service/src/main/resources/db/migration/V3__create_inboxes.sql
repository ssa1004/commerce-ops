-- Inbox 패턴.
-- 다른 서비스가 발행한 이벤트를 멱등하게 받기 위한 저장소.
-- payment_id / reservation_id의 UNIQUE 제약이 같은 이벤트의 두 번째 처리를 막는다 (at-least-once Kafka).
--
-- 이 테이블들은 Order 도메인의 진실은 아니다 — Order 상태와 비교(reconciliation)할 외부 신호의 거울.

CREATE TABLE payment_inbox (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   BIGINT       NOT NULL UNIQUE,
    order_id     BIGINT       NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,    -- PaymentSucceeded / PaymentFailed
    status       VARCHAR(20)  NOT NULL,    -- SUCCESS / FAILED
    external_ref VARCHAR(64),
    raw_payload  TEXT         NOT NULL,
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_inbox_order_id ON payment_inbox (order_id);

CREATE TABLE inventory_inbox (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT       NOT NULL UNIQUE,
    order_id       BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,  -- InventoryReserved / InventoryReleased
    status         VARCHAR(20)  NOT NULL,
    raw_payload    TEXT         NOT NULL,
    received_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_inbox_order_id ON inventory_inbox (order_id);
