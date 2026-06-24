-- 운영형 SAGA step 로그 (ADR-019 후속).
-- 기존엔 보상 대상(잡아둔 재고)을 OrderService 의 in-memory 리스트로만 들고 있어, 프로세스가
-- reserve 와 compensate 사이에서 죽으면 보상 정보가 사라지고 재고가 고아로 남았다.
-- 이 테이블은 성공한(또는 시작한) 정방향 step 을 영속해, 실패/크래시 후에도 DONE step 을
-- seq 역순으로 조회해 보상할 수 있게 한다. 보상 결과도 같은 행에 기록한다.
--
-- status:
--   STARTED              -- 정방향 호출 직전 기록(의도 로그). 이 상태로 남는 건 거의 *크래시* 뿐
--                           (호출이 동기적으로 끝나면 DONE/ABORTED 로 확정). 복구 잡의 보상 대상.
--   DONE                 -- 정방향 호출 성공 확정 — 보상 대상.
--   ABORTED              -- 정방향 호출이 동기적으로 실패(재고부족·한도초과 등)해 확정적으로 아무것도
--                           안 일어남 — 보상 불필요.
--   COMPENSATED          -- 보상(역방향) 완료.
--   COMPENSATION_FAILED  -- 보상 실패 — 복구 잡이 재시도, max 초과 시 운영자 신호(metric).
--
-- payload: 보상에 필요한 인자. INVENTORY_RELEASE 의 경우 productId.
CREATE TABLE saga_step (
    id                    BIGSERIAL    PRIMARY KEY,
    order_id              BIGINT       NOT NULL,
    seq                   INT          NOT NULL,        -- 정방향 완료 순서 (보상은 역순)
    step_name             VARCHAR(64)  NOT NULL,        -- 예: INVENTORY_RESERVE
    compensation          VARCHAR(64)  NOT NULL,        -- 예: INVENTORY_RELEASE
    status                VARCHAR(32)  NOT NULL,        -- STARTED / DONE / COMPENSATED / COMPENSATION_FAILED
    payload               VARCHAR(1024),                -- 보상 인자 (INVENTORY_RELEASE → productId)
    compensation_attempts INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    compensated_at        TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_saga_step_order_seq UNIQUE (order_id, seq)
);

CREATE INDEX idx_saga_step_order_id ON saga_step (order_id);
CREATE INDEX idx_saga_step_status ON saga_step (status);
