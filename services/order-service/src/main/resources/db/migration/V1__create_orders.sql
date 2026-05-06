CREATE TABLE orders (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    total_amount NUMERIC(15,2) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT        NOT NULL,
    quantity   INTEGER       NOT NULL CHECK (quantity > 0),
    price      NUMERIC(15,2) NOT NULL CHECK (price >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
