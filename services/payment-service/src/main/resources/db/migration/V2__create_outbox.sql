-- Phase 3 Step 3a 준비 — payment-service 의 transactional outbox 테이블 자리만 먼저 만든다.
-- 현재 PaymentService 는 트랜잭션 커밋 직후 KafkaTemplate 으로 직접 발행 (ADR-009 의 best-effort).
-- "DB 는 SUCCESS 인데 이벤트는 못 갔다" 가 드물게 가능하므로 outbox 로 격상 예정 (ADR-009 후속).
--
-- 이 마이그레이션 단계의 목표:
--  1) 스키마와 인덱스를 미리 적용해 운영 DB 에 새 컬럼이 점진적으로 들어가도 무중단이 되도록.
--  2) 후속 PR 에서 PaymentService 가 OutboxRepository.save() + 별도 폴러 전환할 때 마이그레이션이 한 번에 크지 않도록.
--
-- 아직 사용처 없음 — 빈 테이블로 유지된다. JPA 엔티티 (OutboxEvent) 는 코드 동등성 (Phase 3 에서 polling 도입 시 일관된 매핑)
-- 을 위해 미리 매핑하지만, 어떤 코드 경로도 INSERT 하지 않는다 (PaymentEventPublisher 는 그대로 KafkaTemplate 사용).
-- order-service 의 outbox_events 와 동일 스키마 (사실상 같은 패턴이라 폴러 코드 재사용 가능).

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
