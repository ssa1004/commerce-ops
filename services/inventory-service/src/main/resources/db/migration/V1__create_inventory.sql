CREATE TABLE inventories (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT      NOT NULL UNIQUE,
    available_quantity INTEGER    NOT NULL CHECK (available_quantity >= 0),
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
    CONSTRAINT uq_reservation_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservation_status ON inventory_reservations (status);
