CREATE TABLE inventories (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT      NOT NULL UNIQUE,
    available_quantity INTEGER    NOT NULL CHECK (available_quantity >= 0),
    -- version: JPA @Version 에 매핑되는 낙관적 락 컬럼.
    -- 같은 행을 두 트랜잭션이 동시에 바꾸면 뒤늦게 커밋하는 쪽이 실패함 (DB 레벨 안전망).
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE inventory_reservations (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT       NOT NULL,
    order_id     BIGINT       NOT NULL,
    quantity     INTEGER      NOT NULL CHECK (quantity > 0),
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    released_at  TIMESTAMP WITH TIME ZONE,
    -- (order_id, product_id) UNIQUE 가 멱등성의 핵심: 같은 키로 두 행이 만들어질 수 없음.
    -- 같은 reserve 요청이 두 번 와도 INSERT 가 한 번만 성공 → 재고가 두 번 차감되지 않는다.
    CONSTRAINT uq_reservation_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservation_status ON inventory_reservations (status);
