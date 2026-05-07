-- Inbox 패턴.
-- 다른 서비스가 발행한 이벤트를 멱등하게 (같은 이벤트가 두 번 와도 한 번 처리한 것과 같게) 받기 위한 저장소.
-- payment_id / reservation_id 의 UNIQUE 제약이 같은 이벤트의 두 번째 INSERT 를 막는다.
-- (Kafka 는 at-least-once 라 같은 메시지가 가끔 중복 도달함 — 그걸 여기서 흡수)
--
-- 이 테이블들은 Order 도메인의 진실이 아니다 — Order 상태와 비교 (reconciliation, 정합성 점검) 할
-- 외부 신호의 거울일 뿐.

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
