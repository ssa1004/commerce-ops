-- Transactional outbox.
-- Events are written in the same DB transaction as the domain change. The publish step
-- (Kafka send) runs separately so it can be retried safely without blocking the request,
-- and so a publish failure never leaves the DB and Kafka in disagreement.

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    sent_at        TIMESTAMP WITH TIME ZONE
);

-- 폴러가 미발행 이벤트만 빠르게 가져오도록 partial index (조건이 붙은 인덱스 — PENDING 행만 색인).
CREATE INDEX idx_outbox_pending ON outbox_events (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);
